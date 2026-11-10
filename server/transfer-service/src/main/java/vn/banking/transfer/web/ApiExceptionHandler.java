package vn.banking.transfer.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import vn.banking.transfer.saga.SagaOrchestratorService;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Auth/ledger call failed (outage, timeout, 401, 5xx): fail closed with a coded 503.
    @ExceptionHandler(RestClientException.class)
    ProblemDetail upstreamUnavailable(RestClientException e) {
        log.warn("Upstream call failed: {}", e.getMessage());
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable");
        d.setProperty("code", "UPSTREAM_UNAVAILABLE");
        return d;
    }

    @ExceptionHandler(SagaOrchestratorService.AccountValidationException.class)
    ProblemDetail accountValidation(SagaOrchestratorService.AccountValidationException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        d.setProperty("code", e.getCode());
        return d;
    }

    @ExceptionHandler(SagaOrchestratorService.IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(SagaOrchestratorService.IdempotencyConflictException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        d.setProperty("code", "IDEMPOTENCY_KEY_REUSED");
        return d;
    }

    @ExceptionHandler(SagaOrchestratorService.TransferNotFoundException.class)
    ProblemDetail notFound(SagaOrchestratorService.TransferNotFoundException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        d.setProperty("code", "NOT_FOUND");
        return d;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setProperty("code", "VALIDATION_FAILED");
        detail.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(err -> Map.of("field", err.getField(), "message", String.valueOf(err.getDefaultMessage())))
                .toList());
        return detail;
    }
}
