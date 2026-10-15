package vn.banking.ledger.web.dto;

import java.time.Instant;
import java.util.UUID;

import vn.banking.ledger.entry.LedgerEntry;

public record EntryResponse(
        UUID id,
        UUID transferId,
        UUID accountId,
        String direction,
        long amount,
        Instant createdAt) {

    public static EntryResponse from(LedgerEntry entry) {
        return new EntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getAccountId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getCreatedAt());
    }
}
