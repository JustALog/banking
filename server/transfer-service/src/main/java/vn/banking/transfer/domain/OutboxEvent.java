package vn.banking.transfer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row. The Debezium Outbox Event Router tails this table and
 * publishes each row to the Kafka topic named by {@code aggregateType}, keyed by
 * {@code aggregateId}. Column names follow the Debezium defaults so the connector
 * needs no custom mapping.
 */
@Entity
@Table(name = "outbox_event")
@EntityListeners(OutboxTraceListener.class)
public class OutboxEvent {

    @Id
    private UUID id;

    /** Debezium default field "aggregatetype" -> routed verbatim as the destination topic. */
    @Column(name = "aggregatetype", nullable = false, length = 100)
    private String aggregateType;

    /** Debezium default field "aggregateid" -> becomes the Kafka record key. */
    @Column(name = "aggregateid", nullable = false, length = 200)
    private String aggregateId;

    @Column(name = "type", length = 100)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** W3C trace context of the writing request/listener; Debezium copies it into a Kafka header. */
    @Column(name = "traceparent", length = 55)
    private String traceparent;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String type, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }

    /**
     * Builds an outbox row that Debezium routes to {@code topic}: the EventRouter uses
     * the {@code aggregatetype} column value verbatim as the destination topic name
     * (route.topic.replacement=${routedByValue}).
     */
    public static OutboxEvent forTopic(String topic, String key, String payload) {
        return new OutboxEvent(topic, key, topic, payload);
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTraceparent() {
        return traceparent;
    }

    void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }
}
