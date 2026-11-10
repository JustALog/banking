package vn.banking.ledger.account;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.ledger.entry.LedgerEntry;
import vn.banking.ledger.entry.LedgerEntryRepository;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository repository;
    private final LedgerEntryRepository entries;

    public AccountService(AccountRepository repository, LedgerEntryRepository entries) {
        this.repository = repository;
        this.entries = entries;
    }

    @Transactional
    public Account openAccount(UUID customerId, String currency) {
        String accountNo = generateUniqueAccountNo();
        return repository.save(new Account(customerId, accountNo, currency));
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts(UUID customerId) {
        return repository.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional(readOnly = true)
    public Account getOwnedAccount(UUID accountId, UUID customerId) {
        Account account = repository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.getCustomerId().equals(customerId)) {
            throw new NotAccountOwnerException(accountId, customerId);
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccountUnchecked(UUID accountId) {
        return repository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> listAll() {
        return repository.findTop200ByOrderByCreatedAtDesc();
    }

    /**
     * Admin cash-in, idempotent on `reference` (the Idempotency-Key). shortcut: single CREDIT entry with the reference in transfer_id (no
     * cash/suspense account, so it is not double-entry); add a bank cash account when deposits become real.
     */
    @Transactional
    public Account deposit(UUID accountId, long amount, UUID reference) {
        Account account = repository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        // checked after the row lock, so concurrent retries serialize here
        if (entries.existsByTransferIdAndAccountId(reference, accountId)) {
            return account;
        }
        account.credit(amount);
        entries.save(new LedgerEntry(reference, account.getId(), LedgerEntry.Direction.CREDIT, amount));
        return account;
    }

    private String generateUniqueAccountNo() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder no = new StringBuilder("VN");
            for (int i = 0; i < 10; i++) {
                no.append(RANDOM.nextInt(10));
            }
            if (!repository.existsByAccountNo(no.toString())) {
                return no.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique account number");
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(UUID id) {
            super("Account not found: " + id);
        }
    }

    public static class NotAccountOwnerException extends RuntimeException {
        public NotAccountOwnerException(UUID accountId, UUID customerId) {
            super("Account %s does not belong to customer %s".formatted(accountId, customerId));
        }
    }
}
