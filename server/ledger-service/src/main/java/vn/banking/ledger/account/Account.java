package vn.banking.ledger.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "account_no", nullable = false, unique = true, length = 20)
    private String accountNo;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Minor units (e.g. cents / xu). Never use floating point for money. */
    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Account() {
    }

    public Account(UUID customerId, String accountNo, String currency) {
        this.customerId = customerId;
        this.accountNo = accountNo;
        this.currency = currency;
    }

    public void debit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive");
        }
        if (balance < amount) {
            throw new InsufficientBalanceException(id, balance, amount);
        }
        balance -= amount;
    }

    public void credit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        balance += amount;
    }

    /** Reverses a prior credit. Skips the balance check so a receiver who already spent the money can still be reversed. */
    public void reverseCredit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Reversal amount must be positive");
        }
        balance -= amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(UUID accountId, long balance, long requested) {
            super("Account %s has balance %d, cannot debit %d".formatted(accountId, balance, requested));
        }
    }
}
