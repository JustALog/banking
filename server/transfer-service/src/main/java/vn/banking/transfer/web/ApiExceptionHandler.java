package vn.banking.transfer.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.banking.transfer.saga.SagaOrchestratorService;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SagaOrchestratorService.AccountValidationException.class)
    ProblemDetail accountValidation(SagaOrchestratorService.AccountValidationException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        d.setProperty("code", e.getCode());
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
