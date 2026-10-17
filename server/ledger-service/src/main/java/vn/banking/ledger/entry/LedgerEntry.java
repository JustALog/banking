package vn.banking.ledger.entry;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ledger_entry",
        uniqueConstraints = @UniqueConstraint(name = "uq_entry_transfer_account_direction",
                columnNames = {"transfer_id", "account_id", "direction"}))
public class LedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public LedgerEntry() {
    }

    public LedgerEntry(UUID transferId, UUID accountId, Direction direction, long amount) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
