package vn.banking.transfer.web.dto;

import java.time.Instant;
import java.util.UUID;

import vn.banking.transfer.domain.Transfer;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destAccountId,
        String sourceAccountNo,
        String destAccountNo,
        long amount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        String direction) {

    public static TransferResponse from(Transfer transfer, UUID viewer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getDestAccountId(),
                transfer.getSourceAccountNo(),
                transfer.getDestAccountNo(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus().name(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getUpdatedAt(),
                viewer == null || viewer.equals(transfer.getCustomerId()) ? "OUT" : "IN");
    }
}
