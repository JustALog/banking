#!/usr/bin/env bash
# One-command local demo: start everything (memory-capped), register CDC, run the smoke test.
#   bash scripts/demo.sh          full: build images + smoke test (first run: several minutes)
#   bash scripts/demo.sh --fast   reuse existing images, skip smoke test
# It also creates the test accounts (user/staff/admin@test.local) by starting auth-service with BANKING_SEED_DEMO_ACCOUNTS=true.
# Stop:  docker compose -f docker/docker-compose.yml down      (add -v to wipe the database)
set -euo pipefail
cd "$(dirname "$0")/.."

export BANKING_SEED_DEMO_ACCOUNTS=true   # auth-service seeds one account per role on startup
FAST=false; [ "${1:-}" = "--fast" ] && FAST=true

[ -f docker/keys/jwt-private.pem ] || bash docker/keys/generate-keys.sh

# --wait blocks until every service passes its healthcheck (gateway waits for the backends)
BUILD=--build; $FAST && BUILD=--no-build
docker compose -f docker/docker-compose.yml up -d $BUILD --wait
bash docker/debezium/register.sh
$FAST || bash scripts/smoke.sh

echo
echo "Test accounts (password Test@1234):"
for r in user staff admin; do
  roles=$(curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$r@test.local\",\"password\":\"Test@1234\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 \
    | cut -d. -f2 | { base64 -d 2>/dev/null || true; } | grep -o '"roles":\[[^]]*\]' || true)
  echo "  $r@test.local  ${roles:-login failed}"
done
echo
echo "Gateway:  http://localhost:8080      Swagger: http://localhost:808{1..5}/swagger-ui.html"
echo "Memory:   docker stats --no-stream"
