package vn.banking.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class IdempotencyKeyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<IdempotencyKeyGatewayFilterFactory.Config> {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public IdempotencyKeyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            if (HttpMethod.POST.equals(request.getMethod())) {
                var header = request.getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
                if (header == null || header.trim().isEmpty()) {
                    var response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    byte[] bytes = "{\"status\":400,\"detail\":\"Missing or empty Idempotency-Key header\",\"code\":\"IDEMPOTENCY_KEY_MISSING\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    DataBuffer buffer = response.bufferFactory().wrap(bytes);
                    return response.writeWith(Mono.just(buffer));
                }
            }
            return chain.filter(exchange);
        };
    }

    public static class Config {
    }
}
