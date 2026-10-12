package vn.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a saga fails: fraud rejection, insufficient funds or partner-bank failure.
 */
public record TransferFailedEvent(
        UUID transferId,
        UUID customerId,
        String reason,
        Instant occurredAt) {

    public TransferFailedEvent(UUID transferId, String reason, Instant occurredAt) {
        this(transferId, null, reason, occurredAt);
    }
}
