package vn.banking.ledger.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.validation.Valid;
import vn.banking.ledger.account.Account;
import vn.banking.ledger.account.AccountService;
import vn.banking.ledger.entry.LedgerEntryRepository;
import vn.banking.ledger.web.dto.AccountResponse;
import vn.banking.ledger.web.dto.EntryResponse;
import vn.banking.ledger.web.dto.OpenAccountRequest;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerEntryRepository entryRepository;

    public AccountController(AccountService accountService, LedgerEntryRepository entryRepository) {
        this.accountService = accountService;
        this.entryRepository = entryRepository;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
            Authentication authentication) {
        Account account = accountService.openAccount(currentCustomer(authentication), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/accounts/{id}")
                .buildAndExpand(account.getId())
                .toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> myAccounts(Authentication authentication) {
        return accountService.listAccounts(currentCustomer(authentication)).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id, Authentication authentication) {
        return AccountResponse.from(accountService.getOwnedAccount(id, currentCustomer(authentication)));
    }

    @GetMapping("/{id}/entries")
    public List<EntryResponse> entries(@PathVariable UUID id, Authentication authentication) {
        accountService.getOwnedAccount(id, currentCustomer(authentication));
        return entryRepository.findByAccountIdOrderByCreatedAtDesc(id).stream()
                .map(EntryResponse::from)
                .toList();
    }

    /**
     * Service-to-service lookup (used by transfer-service to validate accounts).
     * Authenticated only - ownership is decided by the caller, which forwards the
     * end-user token.
     */
    @GetMapping("/internal/{id}")
    public AccountResponse internalGet(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccountUnchecked(id));
    }

    static UUID currentCustomer(Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return UUID.fromString(jwtAuth.getToken().getSubject());
    }
}
