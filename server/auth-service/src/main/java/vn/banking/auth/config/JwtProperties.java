package vn.banking.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banking.jwt")
public record JwtProperties(
        String issuer,
        String keyId,
        String privateKeyPath,
        String publicKeyPath,
        Duration ttl) {
}
