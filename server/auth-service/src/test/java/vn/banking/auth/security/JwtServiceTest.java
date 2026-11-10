package vn.banking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import vn.banking.auth.config.JwtProperties;
import vn.banking.auth.customer.Customer;

class JwtServiceTest {

    private static JwtService jwtService;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();

        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        var resourceLoader = new org.springframework.core.io.DefaultResourceLoader() {
            @Override
            public org.springframework.core.io.Resource getResource(String location) {
                return new org.springframework.core.io.ByteArrayResource(privatePem.getBytes()) {
                    @Override
                    public String getFilename() {
                        return "jwt-private.pem";
                    }
                };
            }
        };
        JwtProperties properties = new JwtProperties("banking-auth", "test-key", "mem:private", "mem:public", Duration.ofMinutes(60));
        jwtService = new JwtService(properties, resourceLoader);
    }

    @Test
    void issuedTokenIsValidRsa256JwtWithExpectedClaims() throws Exception {
        Customer customer = new Customer();
        var idField = Customer.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(customer, UUID.randomUUID());

        String token = jwtService.issueToken(customer);

        // Validate signature with the public key
        var jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey).keyID("test-key").build();
        JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource = new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk));
        var processor = new DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
        processor.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, jwkSource));
        JWTClaimsSet claims = processor.process(token, null);

        assertThat(claims.getSubject()).isEqualTo(customer.getId().toString());
        assertThat(claims.getIssuer()).isEqualTo("banking-auth");
        assertThat(claims.getStringListClaim("roles")).containsExactly("ROLE_USER");
        assertThat(claims.getExpirationTime().toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(60)), within(10, ChronoUnit.SECONDS));
        assertThat(JWTParser.parse(token).getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    }

    @Test
    void adminCustomerGetsAdminRole() throws Exception {
        Customer admin = new Customer();
        var idField = Customer.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(admin, UUID.randomUUID());
        admin.setRole(Customer.Role.ADMIN);

        JWTClaimsSet claims = JWTParser.parse(jwtService.issueToken(admin)).getJWTClaimsSet();

        assertThat(claims.getStringListClaim("roles")).containsExactly("ROLE_ADMIN");
    }

    private static String pem(String type, byte[] der) {
        var encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
    }
}
