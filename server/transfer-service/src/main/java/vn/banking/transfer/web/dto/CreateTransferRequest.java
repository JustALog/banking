package vn.banking.transfer.web.dto;

import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequest(
        @NotNull UUID sourceAccountId,
        UUID destAccountId,
        @Pattern(regexp = "^VN\\d{10}$") String destAccountNo,
        @NotNull @Positive long amount,
        @NotNull String currency) {

    @AssertTrue(message = "exactly one of destAccountId or destAccountNo is required")
    public boolean isDestinationGiven() {
        return (destAccountId == null) != (destAccountNo == null);
    }
}
