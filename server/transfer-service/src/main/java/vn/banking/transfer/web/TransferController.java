package vn.banking.transfer.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.banking.transfer.domain.Transfer;
import vn.banking.transfer.saga.SagaOrchestratorService;
import vn.banking.transfer.web.dto.CreateTransferRequest;
import vn.banking.transfer.web.dto.TransferResponse;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final SagaOrchestratorService saga;

    public TransferController(SagaOrchestratorService saga) {
        this.saga = saga;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @Valid @RequestBody CreateTransferRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        UUID customerId = UUID.fromString(jwtAuth.getToken().getSubject());
        String bearerToken = "Bearer " + jwtAuth.getToken().getTokenValue();

        var result = saga.start(idempotencyKey, customerId, request.sourceAccountId(),
                request.destAccountId(), request.amount(), request.currency(), bearerToken);

        // 202 for a newly accepted saga; 200 when an idempotent replay returns the original
        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(TransferResponse.from(result.transfer()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id, Authentication authentication) {
        return TransferResponse.from(saga.getOwned(id, currentCustomer(authentication)));
    }

    @GetMapping
    public List<TransferResponse> myTransfers(Authentication authentication) {
        return saga.listFor(currentCustomer(authentication)).stream()
                .map(TransferResponse::from)
                .toList();
    }

    static UUID currentCustomer(Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return UUID.fromString(jwtAuth.getToken().getSubject());
    }
}
