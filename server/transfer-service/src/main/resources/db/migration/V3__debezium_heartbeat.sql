-- Debezium writes here every heartbeat.interval.ms, so its replication slot keeps advancing even when
-- no transfer happens. Without that the slot never becomes sync-ready on the standby and a failover
-- loses outbox rows. Change events of this table are dropped by the connector (never reach Kafka).
CREATE TABLE debezium_heartbeat (
    id INT         PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL
);
