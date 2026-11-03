package vn.banking.ledger.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/** Minor units, same as Account.balance. Capped so repeated deposits cannot overflow a long. */
public record DepositRequest(@Positive @Max(1_000_000_000_000L) long amount) {
}
