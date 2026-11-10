package vn.banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.banking.events.Topics;
import vn.banking.events.TransferCompletedEvent;
import vn.banking.events.TransferFailedEvent;
import vn.banking.events.TransferRequestedEvent;
import vn.banking.transfer.account.AccountValidationClient;
import vn.banking.transfer.account.CustomerStatusClient;
import vn.banking.transfer.domain.OutboxEvent;
import vn.banking.transfer.domain.OutboxEventRepository;
import vn.banking.transfer.domain.Transfer;
import vn.banking.transfer.domain.TransferRepository;
import vn.banking.transfer.partner.PartnerBankClient;

@ExtendWith(MockitoExtension.class)
class SagaOrchestratorServiceTest {

    @Mock
    TransferRepository transfers;
    @Mock
    OutboxEventRepository outbox;
    @Mock
    AccountValidationClient accountValidation;
    @Mock
    PartnerBankClient partnerBank;
    @Mock
    CustomerStatusClient customerStatus;

    SagaOrchestratorService service;

    UUID customerId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID destId = UUID.randomUUID();
    String idemKey = "key-123";

    @BeforeEach
    void setUp() {
        service = new SagaOrchestratorService(transfers, outbox, accountValidation, partnerBank, customerStatus);
    }

    @Test
    void startCreatesPendingTransferAndTransferRequestedOutboxAtomically() {
        stubAccountsValid();
        when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.start(idemKey, customerId, sourceId, destId, null, 250_000, "VND", "Bearer t");

        assertThat(result.created()).isTrue();
        assertThat(result.transfer().getStatus()).isEqualTo(Transfer.Status.PENDING);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo(Topics.TRANSFER_REQUESTED);
        assertThat(event.getAggregateId()).isEqualTo(sourceId.toString());
        TransferRequestedEvent payload = vn.banking.events.EventJson.read(event.getPayload(), TransferRequestedEvent.class);
        assertThat(payload.amount()).isEqualTo(250_000);
        assertThat(payload.transferId()).isEqualTo(result.transfer().getId());
    }

    @Test
    void replayingSameIdempotencyKeyReturnsOriginalWithoutNewOutbox() {
        Transfer existing = new Transfer(idemKey, customerId, sourceId, destId, 250_000, "VND");
        when(transfers.findByCustomerIdAndIdempotencyKey(customerId, idemKey)).thenReturn(Optional.of(existing));

        var result = service.start(idemKey, customerId, sourceId, destId, null, 250_000, "VND", "Bearer t");

        assertThat(result.created()).isFalse();
        assertThat(result.transfer()).isSameAs(existing);
        verify(transfers, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void sameKeyFromOtherCustomerStartsNewSaga() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        UUID customerB = UUID.randomUUID();
        var source = new AccountValidationClient.LedgerAccount(sourceId, "VN0001", customerB, "VND", 1_000_000);
        var dest = new AccountValidationClient.LedgerAccount(destId, "VN0002", UUID.randomUUID(), "VND", 0);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(source);
        when(accountValidation.fetch(destId, "Bearer t")).thenReturn(dest);
        when(transfers.findByCustomerIdAndIdempotencyKey(customerB, idemKey)).thenReturn(Optional.empty());
        when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.start(idemKey, customerB, sourceId, destId, null, 250_000, "VND", "Bearer t");

        assertThat(result.created()).isTrue();
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        Transfer existing = new Transfer(idemKey, customerId, sourceId, destId, 250_000, "VND");
        when(transfers.findByCustomerIdAndIdempotencyKey(customerId, idemKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, destId, null, 300_000, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.IdempotencyConflictException.class);
        verify(transfers, never()).save(any());
    }

    @Test
    void startRejectsWhenCallerDoesNotOwnSourceAccount() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        var otherCustomer = new AccountValidationClient.LedgerAccount(sourceId, "VN0001", UUID.randomUUID(), "VND", 100);
        when(accountValidation.fetch(any(), anyString())).thenReturn(otherCustomer);

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, destId, null, 100, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.AccountValidationException.class);
        verify(transfers, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void lockedCustomerCannotStartTransfer() {
        when(customerStatus.isActive("Bearer t")).thenReturn(false);

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, destId, null, 100, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.AccountValidationException.class)
                .extracting("code").isEqualTo("CUSTOMER_LOCKED");
        verify(transfers, never()).save(any());
    }

    @Test
    void authOutageFailsClosed() {
        when(customerStatus.isActive("Bearer t")).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, destId, null, 100, "VND", "Bearer t"))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        verify(transfers, never()).save(any());
    }

    @Test
    void destCurrencyMismatchHasCode() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        var source = new AccountValidationClient.LedgerAccount(sourceId, "VN0001", customerId, "VND", 1_000_000);
        var dest = new AccountValidationClient.LedgerAccount(destId, "VN0002", UUID.randomUUID(), "USD", 50);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(source);
        when(accountValidation.fetch(destId, "Bearer t")).thenReturn(dest);

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, destId, null, 100, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.AccountValidationException.class)
                .extracting("code").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void selfTransferHasCode() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        var source = new AccountValidationClient.LedgerAccount(sourceId, "VN0001", customerId, "VND", 1_000_000);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(source);

        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, sourceId, null, 100, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.AccountValidationException.class)
                .extracting("code").isEqualTo("SELF_TRANSFER_NOT_ALLOWED");
    }

    @Test
    void fraudRejectionFailsSagaWithOutboxEvent() {
        Transfer pending = pendingTransfer();
        when(transfers.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.onFraudDecided(new vn.banking.events.FraudDecidedEvent(pending.getId(),
                sourceId, destId, 250_000, "VND", vn.banking.events.FraudDecision.REJECTED, "over limit", Instant.now()));

        assertThat(pending.getStatus()).isEqualTo(Transfer.Status.FAILED);
        assertThat(pending.getFailureReason()).isEqualTo("FRAUD_REJECTED");
        assertOutboxTopic(Topics.TRANSFER_FAILED, TransferFailedEvent.class, "FRAUD_REJECTED");
    }

    @Test
    void ledgerRejectionFailsSagaWithLedgerReason() {
        Transfer pending = pendingTransfer();
        when(transfers.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.onLedgerRejected(new TransferFailedEvent(pending.getId(), "INSUFFICIENT_FUNDS", Instant.now()));

        assertThat(pending.getStatus()).isEqualTo(Transfer.Status.FAILED);
        assertThat(pending.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertOutboxTopic(Topics.TRANSFER_FAILED, TransferFailedEvent.class, "INSUFFICIENT_FUNDS");
    }

    @Test
    void ledgerPostedWithPartnerSuccessCompletesSaga() {
        Transfer pending = pendingTransfer();
        when(transfers.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(partnerBank.executeTransfer(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new PartnerBankClient.PartnerTransferResult(true, null));

        service.onLedgerPosted(new vn.banking.events.LedgerPostedEvent(pending.getId(),
                sourceId, destId, 250_000, "VND", Instant.now()));

        assertThat(pending.getStatus()).isEqualTo(Transfer.Status.COMPLETED);
        assertOutboxTopic(Topics.TRANSFER_COMPLETED, TransferCompletedEvent.class, null);
    }

    @Test
    void partnerTimeoutFailsSagaWithPartnertimeoutReason() {
        Transfer pending = pendingTransfer();
        when(transfers.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(partnerBank.executeTransfer(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new PartnerBankClient.PartnerTransferResult(false, "PARTNER_TIMEOUT"));

        service.onLedgerPosted(new vn.banking.events.LedgerPostedEvent(pending.getId(),
                sourceId, destId, 250_000, "VND", Instant.now()));

        assertThat(pending.getStatus()).isEqualTo(Transfer.Status.FAILED);
        assertThat(pending.getFailureReason()).isEqualTo("PARTNER_TIMEOUT");
        assertOutboxTopic(Topics.TRANSFER_FAILED, TransferFailedEvent.class, "PARTNER_TIMEOUT");
    }

    @Test
    void ledgerPostedForCompletedOrUnknownTransferIsIgnored() {
        when(transfers.findById(any(UUID.class))).thenReturn(Optional.empty());
        service.onLedgerPosted(new vn.banking.events.LedgerPostedEvent(UUID.randomUUID(),
                sourceId, destId, 100, "VND", Instant.now()));
        verify(partnerBank, never()).executeTransfer(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(outbox, never()).save(any());
    }

    @Test
    void startByAccountNoResolvesDest() {
        UUID destCustomer = UUID.randomUUID();
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(
                new AccountValidationClient.LedgerAccount(sourceId, "VN0000000001", customerId, "VND", 1_000_000));
        when(accountValidation.fetchByNo("VN0000000002", "Bearer t")).thenReturn(
                new AccountValidationClient.LedgerAccount(destId, "VN0000000002", destCustomer, "VND", 50));
        when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var t = service.start(idemKey, customerId, sourceId, null, "VN0000000002", 100, "VND", "Bearer t").transfer();

        assertThat(t.getDestAccountId()).isEqualTo(destId);
        assertThat(t.getDestAccountNo()).isEqualTo("VN0000000002");
        assertThat(t.getSourceAccountNo()).isEqualTo("VN0000000001");
        assertThat(t.getDestCustomerId()).isEqualTo(destCustomer);
    }

    @Test
    void startByOwnAccountNoIsSelfTransfer() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        var source = new AccountValidationClient.LedgerAccount(sourceId, "VN0000000001", customerId, "VND", 1);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(source);
        when(accountValidation.fetchByNo("VN0000000001", "Bearer t")).thenReturn(source);
        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, null, "VN0000000001", 100, "VND", "Bearer t"))
                .isInstanceOfSatisfying(SagaOrchestratorService.AccountValidationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SELF_TRANSFER_NOT_ALLOWED"));
    }

    @Test
    void replayByAccountNoReturnsExisting() {
        Transfer existing = new Transfer(idemKey, customerId, sourceId, destId, 250_000, "VND");
        existing.setAccountDetails("VN0000000001", "VN0000000002", UUID.randomUUID());
        when(transfers.findByCustomerIdAndIdempotencyKey(customerId, idemKey)).thenReturn(Optional.of(existing));
        var result = service.start(idemKey, customerId, sourceId, null, "VN0000000002", 250_000, "VND", "Bearer t");
        assertThat(result.created()).isFalse();
    }

    @Test
    void replayByAccountNoWithDifferentNumberIsRejected() {
        Transfer existing = new Transfer(idemKey, customerId, sourceId, destId, 250_000, "VND");
        existing.setAccountDetails("VN0000000001", "VN0123456789", UUID.randomUUID());
        when(transfers.findByCustomerIdAndIdempotencyKey(customerId, idemKey)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.start(idemKey, customerId, sourceId, null, "VN9999999999", 250_000, "VND", "Bearer t"))
                .isInstanceOf(SagaOrchestratorService.IdempotencyConflictException.class);
        verify(transfers, never()).save(any());
    }

    @Test
    void completedEventCarriesDestCustomer() {
        Transfer pending = pendingTransfer();
        UUID destCustomer = UUID.randomUUID();
        pending.setAccountDetails("VN0000000001", "VN0000000002", destCustomer);
        when(transfers.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(partnerBank.executeTransfer(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new PartnerBankClient.PartnerTransferResult(true, null));

        service.onLedgerPosted(new vn.banking.events.LedgerPostedEvent(pending.getId(),
                sourceId, destId, 250_000, "VND", Instant.now()));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        var ev = vn.banking.events.EventJson.read(captor.getValue().getPayload(), TransferCompletedEvent.class);
        assertThat(ev.destCustomerId()).isEqualTo(destCustomer);
    }

    @Test
    void legacyTransferWithNullAccountNoMapsToResponse() {
        var r = vn.banking.transfer.web.dto.TransferResponse.from(pendingTransfer(), customerId);
        assertThat(r.destAccountNo()).isNull();
        assertThat(r.sourceAccountNo()).isNull();
    }

    @Test
    void recipientCanReadCompletedIncoming() {
        UUID recipient = UUID.randomUUID();
        Transfer t = pendingTransfer();
        t.setAccountDetails("A", "B", recipient);
        t.markCompleted();
        when(transfers.findById(t.getId())).thenReturn(Optional.of(t));
        assertThat(service.getOwned(t.getId(), recipient)).isSameAs(t);
    }

    @Test
    void strangerStillGets404() {
        Transfer t = pendingTransfer();
        t.setAccountDetails("A", "B", UUID.randomUUID());
        t.markCompleted();
        when(transfers.findById(t.getId())).thenReturn(Optional.of(t));
        org.junit.jupiter.api.Assertions.assertThrows(SagaOrchestratorService.TransferNotFoundException.class,
                () -> service.getOwned(t.getId(), UUID.randomUUID()));
    }

    @Test
    void recipientCannotSeePendingIncoming() {
        UUID recipient = UUID.randomUUID();
        Transfer t = pendingTransfer();
        t.setAccountDetails("A", "B", recipient);
        when(transfers.findById(t.getId())).thenReturn(Optional.of(t));
        org.junit.jupiter.api.Assertions.assertThrows(SagaOrchestratorService.TransferNotFoundException.class,
                () -> service.getOwned(t.getId(), recipient));
    }

    @Test
    void responseDirectionForViewer() {
        UUID recipient = UUID.randomUUID();
        Transfer t = pendingTransfer();
        t.setAccountDetails("A", "B", recipient);
        
        assertThat(vn.banking.transfer.web.dto.TransferResponse.from(t, customerId).direction()).isEqualTo("OUT");
        assertThat(vn.banking.transfer.web.dto.TransferResponse.from(t, recipient).direction()).isEqualTo("IN");
        assertThat(vn.banking.transfer.web.dto.TransferResponse.from(t, null).direction()).isEqualTo("OUT");
    }

    private Transfer pendingTransfer() {
        Transfer transfer = new Transfer(idemKey, customerId, sourceId, destId, 250_000, "VND");
        try {
            var idField = Transfer.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(transfer, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return transfer;
    }

    private void stubAccountsValid() {
        when(customerStatus.isActive("Bearer t")).thenReturn(true);
        var source = new AccountValidationClient.LedgerAccount(sourceId, "VN0001", customerId, "VND", 1_000_000);
        var dest = new AccountValidationClient.LedgerAccount(destId, "VN0002", UUID.randomUUID(), "VND", 50);
        when(accountValidation.fetch(sourceId, "Bearer t")).thenReturn(source);
        when(accountValidation.fetch(destId, "Bearer t")).thenReturn(dest);
    }

    private void assertOutboxTopic(String expectedTopic, Class<?> payloadType, String expectedField) {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo(expectedTopic);
        Object payload = vn.banking.events.EventJson.read(event.getPayload(), payloadType);
        if (expectedField != null) {
            assertThat(payload.toString()).contains(expectedField);
        }
    }
}
