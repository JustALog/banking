-- W3C trace context per outbox row; Debezium's EventRouter copies it into the Kafka "traceparent" header
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
