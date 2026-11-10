package vn.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published via the transfer-service outbox when the saga reaches COMPLETED.
 */
public record TransferCompletedEvent(
    UUID transferId,
    UUID customerId,
    long amount,
    String currency,
    Instant occurredAt,
    UUID destCustomerId) {

    public TransferCompletedEvent(UUID transferId, UUID customerId, long amount, String currency, Instant occurredAt) {
        this(transferId, customerId, amount, currency, occurredAt, null);
    }

    public TransferCompletedEvent(UUID transferId, long amount, String currency, Instant occurredAt) {
        this(transferId, null, amount, currency, occurredAt, null);
    }
}
