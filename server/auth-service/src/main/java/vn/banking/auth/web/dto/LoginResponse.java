package vn.banking.auth.web.dto;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        CustomerResponse customer) {
}
