#!/usr/bin/env bash
# One-command local demo: build + start everything (memory-capped), register CDC, run the smoke test.
#   bash scripts/demo.sh          (from server/)
# Stop:  docker compose -f docker/docker-compose.yml down      (add -v to wipe the database)
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f docker/keys/jwt-private.pem ] || bash docker/keys/generate-keys.sh

# --wait blocks until every service passes its healthcheck (gateway waits for the backends)
docker compose -f docker/docker-compose.yml up -d --build --wait
bash docker/debezium/register.sh
bash scripts/smoke.sh

echo
echo "Gateway:  http://localhost:8080      Swagger: http://localhost:808{1..5}/swagger-ui.html"
echo "Memory:   docker stats --no-stream"
