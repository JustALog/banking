package vn.banking.auth.security;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import vn.banking.auth.config.JwtProperties;
import vn.banking.auth.customer.Customer;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final PrivateKey privateKey;

    public JwtService(JwtProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.privateKey = loadPrivateKey(resourceLoader, properties.privateKeyPath());
    }

    public String issueToken(Customer customer) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(customer.getId().toString())
                .claim("email", customer.getEmail())
                .claim("roles", List.of("ROLE_" + customer.getRole().name()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.ttl())))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(properties.keyId())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT for customer " + customer.getId(), e);
        }
    }

    public Instant expiryOf(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime().toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid token", e);
        }
    }

    private static PrivateKey loadPrivateKey(ResourceLoader loader, String location) {
        try (InputStream in = loader.getResource(location).getInputStream()) {
            String pem = new String(in.readAllBytes()).replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA private key from " + location, e);
        }
    }

    static PublicKey loadPublicKey(ResourceLoader loader, String location) {
        try (InputStream in = loader.getResource(location).getInputStream()) {
            String pem = new String(in.readAllBytes()).replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA public key from " + location, e);
        }
    }
}
