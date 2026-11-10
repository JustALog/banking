package vn.banking.transfer.account;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Validates accounts against the ledger service before a saga starts. Forwards the
 * caller's JWT so the ledger authenticates the request; ownership of the source
 * account is enforced here (customer from token must own it).
 */
@Component
public class AccountValidationClient {

    private final RestClient restClient;

    public AccountValidationClient(
            @Value("${banking.ledger.base-url:http://localhost:8082}") String ledgerBaseUrl,
            RestClient.Builder builder) {
        this.restClient = builder.baseUrl(ledgerBaseUrl).build();
    }

    public LedgerAccount fetch(UUID accountId, String bearerToken) {
        try {
            return restClient.get()
                    .uri("/accounts/internal/{id}", accountId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(LedgerAccount.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public LedgerAccount fetchByNo(String accountNo, String bearerToken) {
        try {
            return restClient.get()
                    .uri("/accounts/internal/by-no/{no}", accountNo)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(LedgerAccount.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public record LedgerAccount(UUID id, String accountNo, UUID customerId, String currency, long balance) {
    }
}
