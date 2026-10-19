package vn.banking.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

class OutboxTraceListenerTest {

    private final Tracer tracer = mock(Tracer.class);
    private final Propagator propagator = mock(Propagator.class);
    private final OutboxTraceListener listener = new OutboxTraceListener(tracer, propagator);

    @Test
    @SuppressWarnings("unchecked")
    void stampsTraceparentFromCurrentSpan() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        doAnswer(inv -> {
            ((Propagator.Setter<Object>) inv.getArgument(2)).set(inv.getArgument(1), "traceparent", traceparent);
            return null;
        }).when(propagator).inject(any(), any(), any());

        OutboxEvent event = OutboxEvent.forTopic("transfer.requested", "key", "{}");
        listener.stamp(event);

        assertThat(event.getTraceparent()).isEqualTo(traceparent);
    }

    @Test
    void leavesTraceparentEmptyWithoutSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        OutboxEvent event = OutboxEvent.forTopic("transfer.requested", "key", "{}");
        listener.stamp(event);

        assertThat(event.getTraceparent()).isNull();
    }
}
