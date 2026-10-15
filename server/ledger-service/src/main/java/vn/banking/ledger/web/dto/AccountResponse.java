package vn.banking.ledger.web.dto;

import java.time.Instant;
import java.util.UUID;

import vn.banking.ledger.account.Account;

public record AccountResponse(
        UUID id,
        String accountNo,
        UUID customerId,
        String currency,
        long balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNo(),
                account.getCustomerId(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt());
    }
}
