#!/usr/bin/env bash
# Upserts the Debezium Outbox Event Router connector for transfer_db (safe to re-run).
# Run after `docker compose up` (kafka-connect healthy, transfer-service has created outbox_event):
#   bash docker/debezium/register.sh
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8086}"

until curl -sf "$CONNECT_URL/connectors" >/dev/null; do echo "waiting for Kafka Connect at $CONNECT_URL..."; sleep 5; done

curl -sf -X PUT "$CONNECT_URL/connectors/transfer-outbox-router/config" \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON'
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "postgres",
  "database.port": "5432",
  "database.user": "transfer",
  "database.password": "transfer",
  "database.dbname": "transfer_db",
  "topic.prefix": "banking",
  "table.include.list": "public.outbox_event,public.debezium_heartbeat",
  "heartbeat.interval.ms": "10000",
  "heartbeat.action.query": "INSERT INTO debezium_heartbeat (id, ts) VALUES (1, now()) ON CONFLICT (id) DO UPDATE SET ts = EXCLUDED.ts",
  "snapshot.mode": "initial",
  "plugin.name": "pgoutput",
  "publication.autocreate.mode": "filtered",

  "transforms": "dropHeartbeat,outbox",
  "transforms.dropHeartbeat.type": "org.apache.kafka.connect.transforms.Filter",
  "transforms.dropHeartbeat.predicate": "isHeartbeatTable",
  "predicates": "isHeartbeatTable",
  "predicates.isHeartbeatTable.type": "org.apache.kafka.connect.transforms.predicates.TopicNameMatches",
  "predicates.isHeartbeatTable.pattern": "banking\\.public\\.debezium_heartbeat",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.route.by.field": "aggregatetype",
  "transforms.outbox.route.topic.replacement": "${routedByValue}",
  "transforms.outbox.table.field.event.key": "aggregateid",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.table.fields.additional.placement": "traceparent:header:traceparent",

  "tombstones.on.delete": "false",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
JSON

echo
echo "Connector registered. Status:"
sleep 3
curl -s "$CONNECT_URL/connectors/transfer-outbox-router/status" | head -c 2000
echo
