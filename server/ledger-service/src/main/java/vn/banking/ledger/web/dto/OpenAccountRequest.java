package vn.banking.ledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @NotBlank @Pattern(regexp = "VND|USD|EUR") String currency) {
}
