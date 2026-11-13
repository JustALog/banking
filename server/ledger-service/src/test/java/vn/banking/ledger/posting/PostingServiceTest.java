package vn.banking.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.FraudDecision;
import vn.banking.events.LedgerPostedEvent;
import vn.banking.events.Topics;
import vn.banking.events.TransferFailedEvent;
import vn.banking.ledger.account.Account;
import vn.banking.ledger.account.AccountRepository;
import vn.banking.ledger.entry.LedgerEntry;
import vn.banking.ledger.entry.LedgerEntryRepository;
import vn.banking.ledger.kafka.KafkaEventPublisher;

@ExtendWith(MockitoExtension.class)
class PostingServiceTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    LedgerEntryRepository entryRepository;

    @Mock
    KafkaEventPublisher publisher;

    PostingService service;

    UUID sourceId = UUID.randomUUID();
    UUID destId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PostingService(accountRepository, entryRepository, publisher);
    }

    @Test
    void postsDoubleEntryAndPublishesLedgerPostedWhenApproved() {
        Account source = accountWith(sourceId, 1_000_000);
        Account dest = accountWith(destId, 500);
        when(entryRepository.existsByTransferId(transferId)).thenReturn(false);
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        service.onFraudDecided(event(FraudDecision.APPROVED, 300_000));

        assertThat(source.getBalance()).isEqualTo(700_000);
        assertThat(dest.getBalance()).isEqualTo(300_500);

        ArgumentCaptor<LedgerEntry> entries = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(LedgerEntry.Direction.DEBIT, LedgerEntry.Direction.CREDIT);

        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_POSTED),
                org.mockito.ArgumentMatchers.eq(sourceId.toString()), any(LedgerPostedEvent.class));
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_REJECTED),
                any(), any());
    }

    @Test
    void insufficientFundsFailsWithoutAnyEntry() {
        Account source = accountWith(sourceId, 100);
        Account dest = accountWith(destId, 500);
        when(entryRepository.existsByTransferId(transferId)).thenReturn(false);
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        service.onFraudDecided(event(FraudDecision.APPROVED, 300_000));

        assertThat(source.getBalance()).isEqualTo(100);
        assertThat(dest.getBalance()).isEqualTo(500);
        verify(entryRepository, never()).save(any());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_POSTED), any(), any());
        ArgumentCaptor<TransferFailedEvent> failed = ArgumentCaptor.forClass(TransferFailedEvent.class);
        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_REJECTED),
                org.mockito.ArgumentMatchers.eq(sourceId.toString()), failed.capture());
        assertThat(failed.getValue().reason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void duplicateTransferIdIsIdempotentAndRepublishes() {
        when(entryRepository.existsByTransferId(transferId)).thenReturn(true);

        service.onFraudDecided(event(FraudDecision.APPROVED, 300_000));

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_POSTED),
                org.mockito.ArgumentMatchers.eq(sourceId.toString()), any(LedgerPostedEvent.class));
    }

    @Test
    void rejectedDecisionIsIgnored() {
        service.onFraudDecided(event(FraudDecision.REJECTED, 100));

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
        verify(publisher, never()).publish(any(), any(), any());
    }

    @Test
    void selfTransferIsRejected() {
        UUID same = UUID.randomUUID();
        when(entryRepository.existsByTransferId(transferId)).thenReturn(false);

        service.onFraudDecided(new FraudDecidedEvent(transferId, same, same, 100, "VND",
                FraudDecision.APPROVED, "ok", Instant.now()));

        ArgumentCaptor<TransferFailedEvent> failed = ArgumentCaptor.forClass(TransferFailedEvent.class);
        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(Topics.LEDGER_REJECTED),
                org.mockito.ArgumentMatchers.eq(same.toString()), failed.capture());
        assertThat(failed.getValue().reason()).isEqualTo("SELF_TRANSFER_NOT_ALLOWED");
    }

    @Test
    void accountsAreLockedInSortedOrderToAvoidDeadlocks() {
        Account source = accountWith(sourceId, 1_000_000);
        Account dest = accountWith(destId, 500);
        when(entryRepository.existsByTransferId(transferId)).thenReturn(false);
        when(accountRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(id.equals(sourceId) ? source : dest);
        });

        service.onFraudDecided(event(FraudDecision.APPROVED, 1));

        var inOrder = org.mockito.Mockito.inOrder(accountRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(sourceId.compareTo(destId) < 0 ? sourceId : destId);
        inOrder.verify(accountRepository).findByIdForUpdate(sourceId.compareTo(destId) < 0 ? destId : sourceId);
        assertThat(source.getBalance()).isEqualTo(999_999);
    }

    @Test
    void transferFailedReversesBalancesWhenPostedEntriesExist() {
        Account source = accountWith(sourceId, 800_000);
        Account dest = accountWith(destId, 200_000);

        LedgerEntry originalDebit = new LedgerEntry(transferId, sourceId, LedgerEntry.Direction.DEBIT, 200_000);
        LedgerEntry originalCredit = new LedgerEntry(transferId, destId, LedgerEntry.Direction.CREDIT, 200_000);
        when(entryRepository.findByTransferId(transferId)).thenReturn(List.of(originalDebit, originalCredit));

        when(accountRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(id.equals(sourceId) ? source : dest);
        });

        service.onTransferFailed(new TransferFailedEvent(transferId, "PARTNER_TIMEOUT", Instant.now()));

        assertThat(source.getBalance()).isEqualTo(1_000_000);
        assertThat(dest.getBalance()).isEqualTo(0);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<LedgerEntry> saved = captor.getAllValues();
        assertThat(saved).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(LedgerEntry.Direction.CREDIT, LedgerEntry.Direction.DEBIT);
    }

    @Test
    void transferFailedIgnoredWhenNoEntriesExist() {
        when(entryRepository.findByTransferId(transferId)).thenReturn(List.of());

        service.onTransferFailed(new TransferFailedEvent(transferId, "FRAUD_REJECTED", Instant.now()));

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void transferFailedIgnoredWhenAlreadyReversed() {
        LedgerEntry originalDebit = new LedgerEntry(transferId, sourceId, LedgerEntry.Direction.DEBIT, 200_000);
        LedgerEntry originalCredit = new LedgerEntry(transferId, destId, LedgerEntry.Direction.CREDIT, 200_000);
        LedgerEntry reversalCredit = new LedgerEntry(transferId, sourceId, LedgerEntry.Direction.CREDIT, 200_000);
        LedgerEntry reversalDebit = new LedgerEntry(transferId, destId, LedgerEntry.Direction.DEBIT, 200_000);
        when(entryRepository.findByTransferId(transferId)).thenReturn(List.of(originalDebit, originalCredit, reversalCredit, reversalDebit));

        service.onTransferFailed(new TransferFailedEvent(transferId, "PARTNER_TIMEOUT", Instant.now()));

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void transferFailedRefundsSourceEvenWhenDestAlreadySpentTheMoney() {
        Account source = accountWith(sourceId, 800_000);
        Account dest = accountWith(destId, 0); // receiver already moved the 200_000 away
        when(entryRepository.findByTransferId(transferId)).thenReturn(List.of(
                new LedgerEntry(transferId, sourceId, LedgerEntry.Direction.DEBIT, 200_000),
                new LedgerEntry(transferId, destId, LedgerEntry.Direction.CREDIT, 200_000)));
        when(accountRepository.findByIdForUpdate(any())).thenAnswer(inv ->
                Optional.of(inv.getArgument(0).equals(sourceId) ? source : dest));

        service.onTransferFailed(new TransferFailedEvent(transferId, "PARTNER_TIMEOUT", Instant.now()));

        assertThat(source.getBalance()).isEqualTo(1_000_000);
        assertThat(dest.getBalance()).isEqualTo(-200_000);
        verify(entryRepository, org.mockito.Mockito.times(2)).save(any());
    }

    private FraudDecidedEvent event(FraudDecision decision, long amount) {
        return new FraudDecidedEvent(transferId, sourceId, destId, amount, "VND",
                decision, decision == FraudDecision.APPROVED ? "ok" : "amount too high", Instant.now());
    }

    private static Account accountWith(UUID id, long balance) {
        Account account = new Account();
        try {
            var idField = Account.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(account, id);
            var balanceField = Account.class.getDeclaredField("balance");
            balanceField.setAccessible(true);
            balanceField.set(account, balance);
            var currencyField = Account.class.getDeclaredField("currency");
            currencyField.setAccessible(true);
            currencyField.set(account, "VND");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return account;
    }
}
