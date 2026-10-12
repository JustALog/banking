package vn.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by ledger-service after the double-entry has been committed.
 */
public record LedgerPostedEvent(
    UUID transferId,
    UUID sourceAccountId,
    UUID destAccountId,
    long amount,
    String currency,
    Instant occurredAt) {
}
