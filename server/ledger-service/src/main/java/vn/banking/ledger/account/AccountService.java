package vn.banking.ledger.account;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.ledger.entry.LedgerEntry;
import vn.banking.ledger.entry.LedgerEntryRepository;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ACCOUNTS_PER_CUSTOMER = 10;

    private final AccountRepository repository;
    private final LedgerEntryRepository entries;

    public AccountService(AccountRepository repository, LedgerEntryRepository entries) {
        this.repository = repository;
        this.entries = entries;
    }

    @Transactional
    public Account openAccount(UUID customerId, String currency) {
        if (repository.countByCustomerId(customerId) >= MAX_ACCOUNTS_PER_CUSTOMER) {
            throw new AccountLimitException();
        }
        String accountNo = generateUniqueAccountNo();
        return repository.save(new Account(customerId, accountNo, currency));
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts(UUID customerId) {
        return repository.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional(readOnly = true)
    public Account getOwnedAccount(UUID accountId, UUID customerId) {
        // foreign accounts answer exactly like missing ones, so ids cannot be probed
        return repository.findById(accountId)
                .filter(account -> account.getCustomerId().equals(customerId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public Account getAccountByNo(String accountNo) {
        return repository.findByAccountNo(accountNo)
                .orElseThrow(() -> new AccountNotFoundException(null));
    }

    @Transactional(readOnly = true)
    public Account getAccountUnchecked(UUID accountId) {
        return repository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> listAll(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repository.findByAccountNoContainingIgnoreCaseOrderByCreatedAtDesc(q.trim(), pageable);
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
        if (entries.existsByTransferId(reference)) {
            throw new DepositReferenceConflictException();
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
            super("Account not found");
        }
    }

    public static class DepositReferenceConflictException extends RuntimeException {
        public DepositReferenceConflictException() {
            super("Idempotency-Key already used by another entry");
        }
    }

    public static class AccountLimitException extends RuntimeException {
        public AccountLimitException() {
            super("Account limit reached");
        }
    }
}
