package vn.banking.transfer.security;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Reads the RS256 public key PEM shared by all services (issued by auth-service).
 */
public final class RsaKeys {

    private RsaKeys() {
    }

    public static RSAPublicKey readPublicKey(String location) {
        try {
            Resource resource = new DefaultResourceLoader().getResource(location);
            try (InputStream in = resource.getInputStream()) {
                String pem = new String(in.readAllBytes())
                        .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] der = Base64.getDecoder().decode(pem);
                return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA public key from " + location, e);
        }
    }
}
