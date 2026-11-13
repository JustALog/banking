package vn.banking.auth.web;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import vn.banking.auth.customer.Customer;
import vn.banking.auth.customer.CustomerService;
import vn.banking.auth.security.JwtService;
import vn.banking.auth.security.LoginThrottle;
import vn.banking.auth.security.TooManyAttemptsException;
import vn.banking.auth.web.dto.CustomerResponse;
import vn.banking.auth.web.dto.LoginRequest;
import vn.banking.auth.web.dto.LoginResponse;
import vn.banking.auth.web.dto.RegisterRequest;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CustomerService customerService;
    private final JwtService jwtService;
    private final LoginThrottle throttle;

    public AuthController(CustomerService customerService, JwtService jwtService, LoginThrottle throttle) {
        this.throttle = throttle;
        this.customerService = customerService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        Customer customer = customerService.register(request.email(), request.password(), request.fullName());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/auth/me")
                .build()
                .toUri();
        return ResponseEntity.created(location).body(session(customer));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        if (!throttle.tryAcquire("ip:" + http.getRemoteAddr(), LoginThrottle.MAX_ATTEMPTS_PER_IP)) {
            throw new TooManyAttemptsException();
        }
        String key = request.email().trim().toLowerCase(Locale.ROOT);
        if (!throttle.tryAcquire(key)) {
            throw new TooManyAttemptsException();
        }
        Customer customer = customerService.authenticate(request.email(), request.password());
        throttle.reset(key);
        return session(customer);
    }

    private LoginResponse session(Customer customer) {
        String token = jwtService.issueToken(customer);
        return new LoginResponse(
                token,
                "Bearer",
                jwtService.expiryOf(token),
                CustomerResponse.from(customer));
    }

    @GetMapping("/me")
    public CustomerResponse me(Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        UUID customerId = UUID.fromString(jwtAuth.getToken().getSubject());
        return CustomerResponse.from(customerService.getById(customerId));
    }
}
