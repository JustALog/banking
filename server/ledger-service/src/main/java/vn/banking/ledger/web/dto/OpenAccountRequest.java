package vn.banking.ledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OpenAccountRequest(
        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "[A-Z]{3}") String currency) {
}
