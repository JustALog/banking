package vn.banking.transfer.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;

/**
 * Runs the client through the real Spring/Resilience4j proxy against a local HTTP stub,
 * so annotation misconfiguration (e.g. @TimeLimiter on a sync method) fails here, not in prod.
 */
@SpringBootTest(classes = PartnerBankClient.class)
@EnableConfigurationProperties(PartnerProperties.class)
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
        TimeLimiterAutoConfiguration.class})
class PartnerBankClientTest {

    static volatile boolean slow;
    static final HttpServer partner = startPartner();

    @DynamicPropertySource
    static void partnerUrl(DynamicPropertyRegistry registry) {
        registry.add("banking.partner.base-url", () -> "http://localhost:" + partner.getAddress().getPort());
        registry.add("banking.partner.execute-path", () -> "/partner/v1/transfers");
    }

    @Autowired
    PartnerBankClient client;

    @AfterEach
    void reset() {
        slow = false;
    }

    @AfterAll
    static void stop() {
        partner.stop(0);
    }

    @Test
    void fastPartnerSucceeds() {
        var result = client.executeTransfer("k1", "a", "b", 100, "VND");

        assertThat(result.success()).isTrue();
    }

    @Test
    void slowPartnerFailsAsPartnerTimeoutWithinReadTimeout() {
        slow = true;
        long start = System.nanoTime();

        var result = client.executeTransfer("k2", "a", "b", 100, "VND");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("PARTNER_TIMEOUT");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4_500);
    }

    private static HttpServer startPartner() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/partner/v1/transfers", ex -> {
                if (slow) {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = "{\"status\":\"ACCEPTED\",\"reference\":\"R1\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
