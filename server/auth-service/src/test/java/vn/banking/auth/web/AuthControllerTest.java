package vn.banking.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import vn.banking.auth.customer.Customer;
import vn.banking.auth.customer.CustomerService;
import vn.banking.auth.security.JwtService;
import vn.banking.auth.security.LoginThrottle;
import vn.banking.auth.security.TooManyAttemptsException;
import vn.banking.auth.web.dto.LoginRequest;
import vn.banking.auth.web.dto.LoginResponse;
import vn.banking.auth.web.dto.RegisterRequest;

class AuthControllerTest {

    private final CustomerService customerService = mock(CustomerService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final AuthController controller = new AuthController(customerService, jwtService, throttle);

    @BeforeEach
    void servletRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void registerReturnsSession() {
        Customer customer = new Customer();
        customer.setEmail("new@banking.vn");
        customer.setFullName("Nguyen Van A");
        when(customerService.register(anyString(), anyString(), anyString())).thenReturn(customer);
        when(jwtService.issueToken(any(Customer.class))).thenReturn("token-123");
        Instant expiresAt = Instant.parse("2026-11-10T01:00:00Z");
        when(jwtService.expiryOf("token-123")).thenReturn(expiresAt);

        ResponseEntity<LoginResponse> response = controller.register(
                new RegisterRequest("new@banking.vn", "Passw0rd!23", "Nguyen Van A"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        LoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isEqualTo("token-123");
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresAt()).isEqualTo(expiresAt);
        assertThat(body.customer().email()).isEqualTo("new@banking.vn");
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr(ip);
        return http;
    }

    @Test
    void loginBlockedByIpLimitNeverChecksPassword() {
        when(throttle.tryAcquire("ip:203.0.113.7", 50)).thenReturn(false);

        assertThatThrownBy(() -> controller.login(new LoginRequest("a@b.vn", "x"), from("203.0.113.7")))
                .isInstanceOf(TooManyAttemptsException.class);
        verify(customerService, never()).authenticate(anyString(), anyString());
    }

    @Test
    void successfulLoginResetsEmailButNotIp() {
        when(throttle.tryAcquire(anyString())).thenReturn(true);
        when(throttle.tryAcquire(anyString(), anyInt())).thenReturn(true);
        Customer customer = new Customer();
        customer.setEmail("a@b.vn");
        customer.setFullName("A");
        when(customerService.authenticate(anyString(), anyString())).thenReturn(customer);
        when(jwtService.issueToken(any(Customer.class))).thenReturn("t");
        when(jwtService.expiryOf("t")).thenReturn(Instant.parse("2026-11-10T01:00:00Z"));

        controller.login(new LoginRequest("a@b.vn", "x"), from("203.0.113.7"));

        verify(throttle).reset("a@b.vn");
        verify(throttle, never()).reset("ip:203.0.113.7");
    }
}
