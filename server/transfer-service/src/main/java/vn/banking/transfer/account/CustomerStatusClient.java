package vn.banking.transfer.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Asks the auth service whether the caller (identified by their forwarded JWT) is still ACTIVE. */
@Component
public class CustomerStatusClient {

    private final RestClient restClient;

    public CustomerStatusClient(
            @Value("${banking.auth.base-url:http://localhost:8081}") String authBaseUrl,
            RestClient.Builder builder) {
        this.restClient = builder.baseUrl(authBaseUrl).build();
    }

    public boolean isActive(String bearerToken) {
        var me = restClient.get()
                .uri("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(Me.class);
        return me != null && "ACTIVE".equals(me.status());
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Me(String status) {
    }
}
