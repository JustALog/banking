package vn.banking.transfer.partner;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/**
 * Calls the (mock) partner bank. The partner is slow or down by design in the demo
 * environment, so every call has a 3s read timeout and is guarded by a Resilience4j
 * circuit breaker (@TimeLimiter is not used: it only accepts CompletionStage methods).
 * Failure surfaces through the fallback as PARTNER_TIMEOUT / PARTNER_FAILED,
 * which the saga records on the transfer and publishes via the outbox.
 */
@Component
public class PartnerBankClient {

    private final RestClient restClient;
    private final PartnerProperties properties;

    public PartnerBankClient(PartnerProperties properties) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @CircuitBreaker(name = "partnerBank", fallbackMethod = "fallback")
    public PartnerTransferResult executeTransfer(String idempotencyKey, String sourceAccountNo,
            String destAccountNo, long amount, String currency) {
        var request = new PartnerTransferRequest(idempotencyKey, sourceAccountNo, destAccountNo, amount, currency);
        PartnerTransferResponse response = restClient.post()
                .uri(properties.executePath())
                .body(request)
                .retrieve()
                .body(PartnerTransferResponse.class);
        if (response == null || !"ACCEPTED".equals(response.status())) {
            throw new PartnerCallException("Partner returned unexpected result");
        }
        return new PartnerTransferResult(true, null);
    }

    @SuppressWarnings("unused")
    private PartnerTransferResult fallback(String idempotencyKey, String sourceAccountNo,
            String destAccountNo, long amount, String currency, Throwable cause) {
        String reason = isTimeout(cause) ? "PARTNER_TIMEOUT" : "PARTNER_FAILED";
        return new PartnerTransferResult(false, reason);
    }

    private static boolean isTimeout(Throwable cause) {
        // A read timeout surfaces as SocketTimeoutException wrapped in ResourceAccessException;
        // an open breaker is CallNotPermittedException (-> PARTNER_FAILED).
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause.getClass().getName().toLowerCase().contains("timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public record PartnerTransferRequest(String idempotencyKey, String sourceAccountNo,
            String destAccountNo, long amount, String currency) {
    }

    public record PartnerTransferResponse(String status, String reference) {
    }

    public record PartnerTransferResult(boolean success, String failureReason) {
    }

    public static class PartnerCallException extends RuntimeException {
        public PartnerCallException(String message) {
            super(message);
        }
    }
}
