package vn.banking.ledger.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.banking.ledger.account.AccountService;
import vn.banking.ledger.web.dto.AccountResponse;
import vn.banking.ledger.web.dto.DepositRequest;

/** ROLE_ADMIN only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/accounts/admin")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountResponse> all() {
        return accountService.listAll().stream().map(AccountResponse::from).toList();
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody DepositRequest request) {
        return AccountResponse.from(accountService.deposit(id, request.amount(), key));
    }
}
