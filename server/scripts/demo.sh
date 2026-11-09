#!/usr/bin/env bash
# One-command local demo: build + start everything (memory-capped), register CDC, run the smoke test.
#   bash scripts/demo.sh          (from server/)  full: build images + smoke test
#   bash scripts/demo.sh --fast   reuse existing images, skip the smoke test
# Stop:  docker compose -f docker/docker-compose.yml down      (add -v to wipe the database)
set -euo pipefail
cd "$(dirname "$0")/.."

FAST=false; [ "${1:-}" = "--fast" ] && FAST=true

[ -f docker/keys/jwt-private.pem ] || bash docker/keys/generate-keys.sh

# --wait blocks until every service passes its healthcheck (gateway waits for the backends)
BUILD=--build; $FAST && BUILD=--no-build
docker compose -f docker/docker-compose.yml up -d $BUILD --wait
bash docker/debezium/register.sh
$FAST || bash scripts/smoke.sh

# Test accounts: the admin comes from AdminBootstrap (compose env); register a plain customer here.
G=http://localhost:8080
post() { curl -s -X POST "$G/auth/$1" -H 'Content-Type: application/json' -d "{\"email\":\"$2\",\"password\":\"$3\"$4}"; }
post register customer@banking.local Customer12345 ',"fullName":"Demo Customer"' >/dev/null || true
echo
echo "Test accounts:"
for acc in admin@banking.local:admin12345 customer@banking.local:Customer12345; do
  out=$(post login "${acc%%:*}" "${acc#*:}")
  echo "  ${acc%%:*}  $(echo "$out" | grep -q accessToken && echo "login OK" || echo "login FAILED")"
done
echo
echo "Gateway:  http://localhost:8080      Swagger: http://localhost:808{1..5}/swagger-ui.html"
echo "Memory:   docker stats --no-stream"
