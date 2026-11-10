package vn.banking.transfer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "transfer")
public class Transfer {

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "dest_account_id", nullable = false)
    private UUID destAccountId;

    @Column(name = "source_account_no", length = 20)
    private String sourceAccountNo;

    @Column(name = "dest_account_no", length = 20)
    private String destAccountNo;

    @Column(name = "dest_customer_id")
    private UUID destCustomerId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    public Transfer() {
    }

    public Transfer(String idempotencyKey, UUID customerId, UUID sourceAccountId, UUID destAccountId,
            long amount, String currency) {
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destAccountId = destAccountId;
        this.amount = amount;
        this.currency = currency;
    }

    public void setAccountDetails(String sourceAccountNo, String destAccountNo, UUID destCustomerId) {
        this.sourceAccountNo = sourceAccountNo;
        this.destAccountNo = destAccountNo;
        this.destCustomerId = destCustomerId;
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.failureReason = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestAccountId() {
        return destAccountId;
    }

    public String getSourceAccountNo() {
        return sourceAccountNo;
    }

    public String getDestAccountNo() {
        return destAccountNo;
    }

    public UUID getDestCustomerId() {
        return destCustomerId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
