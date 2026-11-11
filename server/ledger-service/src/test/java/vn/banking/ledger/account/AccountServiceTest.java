package vn.banking.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.banking.ledger.entry.LedgerEntry;
import vn.banking.ledger.entry.LedgerEntryRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accounts;

    @Mock
    LedgerEntryRepository entries;

    @Test
    void firstAccountOpens() {
        UUID customer = UUID.randomUUID();
        when(accounts.countByCustomerId(customer)).thenReturn(0L);
        when(accounts.existsByAccountNo(any())).thenReturn(false);

        new AccountService(accounts, entries).openAccount(customer, "VND");

        verify(accounts).save(any(Account.class));
    }

    @Test
    void secondAccountRejected() {
        UUID customer = UUID.randomUUID();
        when(accounts.countByCustomerId(customer)).thenReturn(1L);

        assertThatThrownBy(() -> new AccountService(accounts, entries).openAccount(customer, "VND"))
                .isInstanceOf(AccountService.AccountLimitException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    void firstDepositCredits() {
        Account account = new Account(UUID.randomUUID(), "VN0000000001", "VND");
        UUID ref = UUID.randomUUID();
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(entries.existsByTransferIdAndAccountId(ref, account.getId())).thenReturn(false);

        new AccountService(accounts, entries).deposit(account.getId(), 500, ref);

        assertThat(account.getBalance()).isEqualTo(500);
        verify(entries).save(any(LedgerEntry.class));
    }

    @Test
    void foreignAccountLooksMissing() {
        UUID accountId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID otherCustomer = UUID.randomUUID();
        Account account = new Account(owner, "VN0000000001", "VND");
        when(accounts.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> new AccountService(accounts, entries).getOwnedAccount(accountId, otherCustomer))
                .isInstanceOf(AccountService.AccountNotFoundException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .isEqualTo("Account not found")
                        .doesNotContain(accountId.toString())
                        .doesNotContain(otherCustomer.toString())
                        .doesNotContain(owner.toString()));
    }

    @Test
    void replayedDepositDoesNotCreditTwice() {
        Account account = new Account(UUID.randomUUID(), "VN0000000001", "VND");
        UUID ref = UUID.randomUUID();
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(entries.existsByTransferIdAndAccountId(ref, account.getId())).thenReturn(true);

        Account result = new AccountService(accounts, entries).deposit(account.getId(), 500, ref);

        assertThat(result.getBalance()).isZero();
        verify(entries, never()).save(any());
    }

    @Test
    void depositKeyUsedByAnotherAccountIsRejected() {
        Account account = new Account(UUID.randomUUID(), "VN0000000001", "VND");
        UUID ref = UUID.randomUUID();
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(entries.existsByTransferIdAndAccountId(ref, account.getId())).thenReturn(false);
        when(entries.existsByTransferId(ref)).thenReturn(true);

        assertThatThrownBy(() -> new AccountService(accounts, entries).deposit(account.getId(), 500, ref))
                .isInstanceOf(AccountService.DepositReferenceConflictException.class);

        assertThat(account.getBalance()).isZero();
        verify(entries, never()).save(any());
    }

    @Test
    void listAllPassesPageableAndFiltersByAccountNo() {
        var page = org.springframework.data.domain.PageRequest.of(0, 50);

        new AccountService(accounts, entries).listAll(null, page);
        new AccountService(accounts, entries).listAll(" 123 ", page);

        verify(accounts).findAllByOrderByCreatedAtDesc(page);
        verify(accounts).findByAccountNoContainingIgnoreCaseOrderByCreatedAtDesc("123", page);
    }
}
