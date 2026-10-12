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
    Instant occurredAt) {

    public TransferCompletedEvent(UUID transferId, long amount, String currency, Instant occurredAt) {
        this(transferId, null, amount, currency, occurredAt);
    }
}
