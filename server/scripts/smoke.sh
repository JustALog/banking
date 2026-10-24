#!/usr/bin/env bash
# End-to-end happy path against locally running services (docker compose + Spring apps).
# Can run directly against individual service ports or via the API Gateway (:8080).
#
# Prerequisites:
#   0. or just: bash scripts/demo.sh (does all of the below)
#   1. docker compose up -d                    (from server/docker/)
#   2. bash docker/debezium/register.sh        (once)
#   3. services running: auth, ledger, transfer, fraud, notification, gateway (see README)
#   4. docker compose is invocable as `docker compose` for the balance seed
#
# Usage: bash scripts/smoke.sh
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
USE_GATEWAY="${USE_GATEWAY:-true}"

if [ "$USE_GATEWAY" = "true" ]; then
  AUTH_URL="$GATEWAY_URL"
  LEDGER_URL="$GATEWAY_URL"
  TRANSFER_URL="$GATEWAY_URL"
  NOTIFY_URL="$GATEWAY_URL"
else
  AUTH_URL="${AUTH_URL:-http://localhost:8081}"
  LEDGER_URL="${LEDGER_URL:-http://localhost:8082}"
  TRANSFER_URL="${TRANSFER_URL:-http://localhost:8083}"
  NOTIFY_URL="${NOTIFY_URL:-http://localhost:8085}"
fi

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../docker" && pwd)}"
# Superuser psql used for the balance seed; k8s/install.sh prints the kubectl variant
PSQL="${PSQL:-docker compose --project-directory $COMPOSE_DIR exec -T postgres psql -U postgres}"

EMAIL="smoke-$(date +%s)@example.com"
PASSWORD="Passw0rd!23"
AMOUNT=250000

step() { printf '\n=== %s ===\n' "$*"; }

jqget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null \
  || node -e "const d=JSON.parse(require('fs').readFileSync(0,'utf8'));console.log(d$1)" 2>/dev/null; }

step "1. Register customer $EMAIL"
curl -sf -X POST "$AUTH_URL/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test\"}" | head -c 400; echo

step "2. Login"
TOKEN=$(curl -sf -X POST "$AUTH_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jqget "['accessToken']")
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "login failed"; exit 1; }
echo "token acquired (${#TOKEN} chars)"

AUTH="Authorization: Bearer $TOKEN"

step "3. Open two accounts"
SRC=$(curl -sf -X POST "$LEDGER_URL/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"VND"}' | jqget "['id']")
DST=$(curl -sf -X POST "$LEDGER_URL/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"VND"}' | jqget "['id']")
echo "source=$SRC dest=$DST"

step "4. Seed source account balance (dev seed via psql)"
$PSQL -d ledger_db -c "UPDATE account SET balance = 10000000 WHERE id = '$SRC';"

if [ "$USE_GATEWAY" = "true" ]; then
  step "5a. Verify Gateway enforces Idempotency-Key (missing key must return HTTP 400)"
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$TRANSFER_URL/transfers" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"sourceAccountId\":\"$SRC\",\"destAccountId\":\"$DST\",\"amount\":$AMOUNT,\"currency\":\"VND\"}")
  [ "$HTTP_CODE" = "400" ] || { echo "GATEWAY ENFORCEMENT BROKEN: Expected 400, got $HTTP_CODE"; exit 1; }
  echo "missing Idempotency-Key rejected with HTTP 400 as expected"
fi

step "5. Create transfer (Idempotency-Key enforced)"
KEY="smoke-$(date +%s)"
RESP=$(curl -sf -w '\nHTTP:%{http_code}' -X POST "$TRANSFER_URL/transfers" \
  -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccountId\":\"$SRC\",\"destAccountId\":\"$DST\",\"amount\":$AMOUNT,\"currency\":\"VND\"}")
echo "$RESP"
TRANSFER_ID=$(echo "$RESP" | head -1 | jqget "['id']")

step "6. Replay same Idempotency-Key (must return the same transfer id, no new saga)"
RESP2=$(curl -sf -w '\nHTTP:%{http_code}' -X POST "$TRANSFER_URL/transfers" \
  -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccountId\":\"$SRC\",\"destAccountId\":\"$DST\",\"amount\":$AMOUNT,\"currency\":\"VND\"}")
TRANSFER_ID2=$(echo "$RESP2" | head -1 | jqget "['id']")
[ "$TRANSFER_ID" = "$TRANSFER_ID2" ] || { echo "IDEMPOTENCY BROKEN: $TRANSFER_ID != $TRANSFER_ID2"; exit 1; }
echo "idempotent replay OK"

step "7. Poll saga status until terminal (max 30s)"
STATUS="PENDING"
for _ in $(seq 1 30); do
  STATUS=$(curl -sf "$TRANSFER_URL/transfers/$TRANSFER_ID" -H "$AUTH" | jqget "['status']")
  [ "$STATUS" = "COMPLETED" ] && break
  [ "$STATUS" = "FAILED" ] && break
  sleep 1
done
echo "final status: $STATUS"
[ "$STATUS" = "COMPLETED" ] || { echo "saga did not complete"; exit 1; }

step "8. Verify balances and statement"
curl -sf "$LEDGER_URL/accounts/$SRC" -H "$AUTH" | jqget "['balance']"
curl -sf "$LEDGER_URL/accounts/$DST" -H "$AUTH" | jqget "['balance']"
curl -sf "$LEDGER_URL/accounts/$SRC/entries" -H "$AUTH" | head -c 500; echo

step "9. Verify Notification was generated"
sleep 2
NOTIFS=$(curl -sf "$NOTIFY_URL/notifications" -H "$AUTH")
echo "notifications: $NOTIFS" | head -c 500; echo

echo
echo "SMOKE TEST PASSED"
