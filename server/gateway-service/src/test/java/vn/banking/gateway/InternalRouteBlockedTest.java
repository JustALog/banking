package vn.banking.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger's unchecked /accounts/internal/** endpoint must never be routed from outside. */
@SpringBootTest
class InternalRouteBlockedTest {

    @Autowired
    RouteLocator routes;

    /** Same rule the gateway applies at runtime: the first route (by order) whose predicate matches wins. */
    private String firstMatchingRoute(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return routes.getRoutes()
                .concatMap(r -> Mono.from(r.getPredicate().apply(exchange)).filter(Boolean::booleanValue).map(m -> r.getId()))
                .blockFirst();
    }

    @Test
    void internalAccountLookupIsBlocked() {
        assertThat(firstMatchingRoute("/accounts/internal/123e4567-e89b-12d3-a456-426614174000")).isEqualTo("block-internal");
    }

    @Test
    void publicAccountRoutesStillReachLedger() {
        assertThat(firstMatchingRoute("/accounts")).isEqualTo("ledger-service");
        assertThat(firstMatchingRoute("/accounts/123e4567-e89b-12d3-a456-426614174000/entries")).isEqualTo("ledger-service");
    }
}
