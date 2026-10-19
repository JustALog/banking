package vn.banking.transfer.web.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destAccountId,
        @NotNull @Positive long amount,
        @NotNull String currency) {
}
