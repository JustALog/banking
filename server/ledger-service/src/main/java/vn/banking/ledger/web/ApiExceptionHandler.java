package vn.banking.ledger.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import vn.banking.ledger.account.AccountService;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountService.AccountNotFoundException.class)
    ProblemDetail notFound(AccountService.AccountNotFoundException e) {
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

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequestInput(Exception e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Missing or malformed request parameter");
        d.setProperty("code", "VALIDATION_FAILED");
        return d;
    }
}
