package vn.banking.fraud.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import vn.banking.events.FraudDecision;

@Entity
@Table(name = "fraud_evaluation")
public class FraudEvaluation {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "dest_account_id", nullable = false)
    private UUID destAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudDecision decision;

    @Column(name = "rule_triggered", length = 100)
    private String ruleTriggered;

    @Column(columnDefinition = "TEXT")
    private String reason;

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

    public FraudEvaluation() {
    }

    public FraudEvaluation(UUID transferId, UUID sourceAccountId, UUID destAccountId,
            long amount, String currency, FraudDecision decision, String ruleTriggered, String reason) {
        this.transferId = transferId;
        this.sourceAccountId = sourceAccountId;
        this.destAccountId = destAccountId;
        this.amount = amount;
        this.currency = currency;
        this.decision = decision;
        this.ruleTriggered = ruleTriggered;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestAccountId() {
        return destAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public FraudDecision getDecision() {
        return decision;
    }

    public String getRuleTriggered() {
        return ruleTriggered;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
