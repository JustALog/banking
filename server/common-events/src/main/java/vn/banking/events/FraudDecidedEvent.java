package vn.banking.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by fraud-service after evaluating a transfer request.
 * Carries the transfer payload through so downstream services never need a lookup.
 */
public record FraudDecidedEvent(
    UUID transferId,
    UUID sourceAccountId,
    UUID destAccountId,
    long amount,
    String currency,
    FraudDecision decision,
    String reason,
    Instant occurredAt) {
}
