package vn.banking.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyKeyGatewayFilterFactoryTest {

    private final IdempotencyKeyGatewayFilterFactory factory = new IdempotencyKeyGatewayFilterFactory();

    @Test
    void rejectsPostWhenIdempotencyKeyMissing() {
        var request = MockServerHttpRequest.method(HttpMethod.POST, "/transfers").build();
        var exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        factory.apply(new IdempotencyKeyGatewayFilterFactory.Config()).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chain, never()).filter(any());
    }

    @Test
    void acceptsPostWhenIdempotencyKeyPresent() {
        var request = MockServerHttpRequest.method(HttpMethod.POST, "/transfers")
                .header(IdempotencyKeyGatewayFilterFactory.IDEMPOTENCY_KEY_HEADER, "key-123")
                .build();
        var exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        factory.apply(new IdempotencyKeyGatewayFilterFactory.Config()).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    @Test
    void allowsGetWithoutIdempotencyKey() {
        var request = MockServerHttpRequest.method(HttpMethod.GET, "/transfers").build();
        var exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        factory.apply(new IdempotencyKeyGatewayFilterFactory.Config()).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }
}
