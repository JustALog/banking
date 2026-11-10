package vn.banking.auth.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.banking.auth.customer.CustomerService;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CustomerService.DuplicateEmailException.class)
    ProblemDetail duplicateEmail(CustomerService.DuplicateEmailException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        d.setProperty("code", "EMAIL_TAKEN");
        return d;
    }

    @ExceptionHandler(CustomerService.BadCredentialsException.class)
    ProblemDetail badCredentials(CustomerService.BadCredentialsException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        d.setProperty("code", "BAD_CREDENTIALS");
        return d;
    }

    @ExceptionHandler(CustomerService.CustomerLockedException.class)
    ProblemDetail locked(CustomerService.CustomerLockedException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, e.getMessage());
        d.setProperty("code", "CUSTOMER_LOCKED");
        return d;
    }

    @ExceptionHandler(CustomerService.CustomerNotFoundException.class)
    ProblemDetail notFound(CustomerService.CustomerNotFoundException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        d.setProperty("code", "NOT_FOUND");
        return d;
    }

    @ExceptionHandler(CustomerService.SelfLockException.class)
    ProblemDetail selfLock(CustomerService.SelfLockException e) {
        ProblemDetail d = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        d.setProperty("code", "SELF_LOCK");
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
