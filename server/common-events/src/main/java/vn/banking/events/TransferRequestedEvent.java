package vn.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the Debezium outbox router when transfer-service commits a new
 * saga.
 */
public record TransferRequestedEvent(
        UUID transferId,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID destAccountId,
        long amount,
        String currency,
        Instant occurredAt) {
}
