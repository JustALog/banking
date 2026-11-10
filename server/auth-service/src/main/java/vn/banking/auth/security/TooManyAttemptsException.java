package vn.banking.auth.security;

public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException() {
        super("Too many failed login attempts, try again later");
    }
}
