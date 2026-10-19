package vn.banking.transfer.domain;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.persistence.PrePersist;
import org.springframework.stereotype.Component;

/**
 * Stamps each outbox row with the current trace context so the trace survives the
 * DB -> Debezium -> Kafka hop (the EventRouter places the column in a "traceparent" header).
 * Spring-managed: Boot wires Hibernate's bean container to Spring.
 */
@Component
public class OutboxTraceListener {

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTraceListener(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @PrePersist
    void stamp(OutboxEvent event) {
        Span span = tracer.currentSpan();
        if (span == null) {
            return;
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        event.setTraceparent(headers.get("traceparent"));
    }
}
