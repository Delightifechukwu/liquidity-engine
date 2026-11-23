#!/usr/bin/env bash
set -euo pipefail

PROJECT="${COMPOSE_PROJECT_NAME:-liquidity-engine}"
BASE_URL="${ORDER_BASE_URL:-http://localhost:8081}/api/v1/orders"
seller=$(python3 -c 'import uuid; print(uuid.uuid4())')
buyer=$(python3 -c 'import uuid; print(uuid.uuid4())')
work=$(mktemp -d)
broker_stopped=false
checkpoint_blocked=false
provider_stopped=false
order_stopped=false
watch_pid=
cleanup() {
  if [[ -n "$watch_pid" ]]; then kill "$watch_pid" 2>/dev/null || true; fi
  if "$checkpoint_blocked"; then unblock_checkpoint || true; fi
  if "$order_stopped"; then docker compose start order-service >&2 || true; fi
  if "$provider_stopped"; then docker compose start liquidity-provider-adapter >&2 || true; fi
  if "$broker_stopped"; then docker compose start kafka >&2 || true; fi
  rm -f "$work/body" "$work/a" "$work/b" "$work/ready" "$work/delivered" "$work/replay-gate"
  rmdir "$work"
}
trap cleanup EXIT

sql() {
  docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -Atq -U liquidity -d order_service
}

unblock_checkpoint() {
  sql <<'SQL'
DROP TRIGGER IF EXISTS e2e_block_trade_checkpoint ON trade_outbox;
DROP FUNCTION IF EXISTS e2e_block_trade_checkpoint();
SQL
}

wait_for() {
  local deadline=$((SECONDS + 90))
  until "$@"; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for $*" >&2
      return 1
    fi
    sleep 1
  done
}

token() {
  docker run --rm --network "${PROJECT}_default" curlimages/curl:8.12.1 -fsS --max-time 10 \
    http://oauth:8080/liquidity-engine/token \
    -d grant_type=client_credentials -d client_secret=demo -d "client_id=$1" -d scope=liquidity-engine \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])'
}

request() {
  local expected=$1 method=$2 path=$3 bearer=$4
  shift 4
  local status
  status=$(curl -sS --max-time 10 -o "$work/body" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
    -H "Authorization: Bearer $bearer" "$@")
  if [[ "$status" != "$expected" ]]; then
    echo "Expected $expected for $method $path, got $status" >&2
    cat "$work/body" >&2
    return 1
  fi
}

place() {
  request "$1" POST '' "$2" -H "Idempotency-Key: $3" -H 'Content-Type: application/json' -d "$4"
}

field() {
  python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1" < "$work/body"
}

ready() {
  [[ $(curl -s --max-time 2 -o /dev/null -w '%{http_code}' "$BASE_URL") == 401 ]]
}

wait_for ready
seller_token=$(token "$seller")
buyer_token=$(token "$buyer")

sql <<SQL
BEGIN;
INSERT INTO wallet_opening_balances VALUES ('$seller','BTC',2), ('$buyer','NGN',50000000);
INSERT INTO wallets (user_id, asset, balance)
SELECT user_id, asset, amount FROM wallet_opening_balances WHERE user_id IN ('$seller', '$buyer');
COMMIT;
SQL

ask='{"market":"BTC-NGN","side":"SELL","type":"LIMIT","price":"49000000","quantity":"2"}'
bid='{"market":"BTC-NGN","side":"BUY","type":"LIMIT","price":"50000000","quantity":"1"}'
place 201 "$seller_token" shared-key "$ask"
sell_id=$(field orderId)
test "$(field status)" = OPEN
request 404 GET "/$sell_id" "$buyer_token"
request 404 DELETE "/$sell_id" "$buyer_token"
place 400 "$buyer_token" forged-user "{\"userId\":\"$seller\",${bid:1}"
place 400 "$seller_token" overspend "$ask"
place 400 "$seller_token" unsupported-stop '{"market":"BTC-NGN","side":"SELL","type":"STOP","price":"49000000","stopPrice":"47500000","quantity":"1"}'
route_preview() {
  curl -fsS --max-time 10 -H "Authorization: Bearer $buyer_token" \
    "${BASE_URL%/orders}/routes/buy-preview?maxPrice=$1&quantity=$2"
}

docker compose stop liquidity-provider-adapter
provider_stopped=true
preview=$(route_preview 49000000 1)
python3 -c 'import json, sys; p=json.loads(sys.argv[1]); assert p == {"venue": "INTERNAL", "price": 49000000}, p' "$preview"
docker compose start liquidity-provider-adapter
provider_stopped=false

docker compose stop kafka
broker_stopped=true
docker compose restart order-service
wait_for ready

# Race concurrent redeliveries and verify one committed order while Kafka is down.
for output in a b; do
  curl -sS --max-time 10 -w '\n%{http_code}\n' -X POST "$BASE_URL" \
    -H "Authorization: Bearer $buyer_token" -H 'Idempotency-Key: shared-key' \
    -H 'Content-Type: application/json' -d "$bid" > "$work/$output" &
  if [[ "$output" == a ]]; then first_pid=$!; else second_pid=$!; fi
done
wait "$first_pid"
wait "$second_pid"
buy_id=$(python3 - "$work/a" "$work/b" <<'PY'
import json
import pathlib
import sys

responses = [pathlib.Path(p).read_text().splitlines() for p in sys.argv[1:]]
assert sorted(r[-1] for r in responses) == ["200", "201"], responses
orders = [json.loads("\n".join(r[:-1])) for r in responses]
assert orders[0]["orderId"] == orders[1]["orderId"], orders
assert all(order["status"] == "FILLED" for order in orders), orders
print(orders[0]["orderId"])
PY
)

request 200 GET "/$sell_id" "$seller_token"
test "$(field status)" = PARTIALLY_FILLED
request 200 DELETE "/$sell_id" "$seller_token"
test "$(field status)" = CANCELLED
test "$(field filledQuantity)" = 1.0
place 201 "$seller_token" unfilled-ioc '{"market":"BTC-NGN","side":"SELL","type":"MARKET","price":"49000000","quantity":"1"}'
test "$(field status)" = CANCELLED

# Two distinct orders compete for the same remaining BTC. Only one can reserve it.
for output in a b; do
  curl -sS --max-time 10 -w '\n%{http_code}\n' -X POST "$BASE_URL" \
    -H "Authorization: Bearer $seller_token" -H "Idempotency-Key: reserve-$output" \
    -H 'Content-Type: application/json' \
    -d '{"market":"BTC-NGN","side":"SELL","type":"LIMIT","price":"49000000","quantity":"1"}' > "$work/$output" &
  if [[ "$output" == a ]]; then first_pid=$!; else second_pid=$!; fi
done
wait "$first_pid"
wait "$second_pid"
reserved_order=$(python3 - "$work/a" "$work/b" <<'PYTEST'
import json
import pathlib
import sys

responses = [pathlib.Path(p).read_text().splitlines() for p in sys.argv[1:]]
assert sorted(r[-1] for r in responses) == ["201", "400"], responses
created = next(json.loads("\n".join(r[:-1])) for r in responses if r[-1] == "201")
assert created["status"] == "OPEN", created
print(created["orderId"])
PYTEST
)
request 200 DELETE "/$reserved_order" "$seller_token"
test "$(field status)" = CANCELLED

trade_id=$(sql <<SQL
SELECT id FROM trading_fills WHERE buy_order_id = '$buy_id' AND sell_order_id = '$sell_id';
SQL
)
test -n "$trade_id"
test "$(sql <<< "SELECT count(*) FROM trading_postings WHERE trade_id = '$trade_id'")" = 4
test "$(sql <<< "SELECT count(*) FROM trade_outbox WHERE trade_id = '$trade_id' AND published_at IS NULL")" = 1
test "$(sql <<< "SELECT reserved = 0 AND balance = 1 FROM wallets WHERE user_id = '$seller' AND asset = 'BTC'")" = t
test "$(sql <<< "SELECT reserved = 0 AND balance = 1000000 FROM wallets WHERE user_id = '$buyer' AND asset = 'NGN'")" = t
test "$(sql <<< 'SELECT count(*) FROM trading_discrepancies')" = 0
missing=$(sql <<SQL
BEGIN;
DELETE FROM trading_postings WHERE trade_id = '$trade_id';
SELECT count(*) FROM trading_discrepancies WHERE kind = 'POSTING' AND reference = '$trade_id';
ROLLBACK;
SQL
)
test "$missing" = 4

# Force the checkpoint to roll back after the broker accepts this trade only.
# A real WebSocket client observes the initial publication and the replay.
sql <<SQL
BEGIN;
CREATE FUNCTION e2e_block_trade_checkpoint() RETURNS trigger LANGUAGE plpgsql AS \$\$
BEGIN
    IF NEW.trade_id = '$trade_id' AND NEW.published_at IS NOT NULL THEN
        RAISE EXCEPTION 'e2e: Kafka acknowledged but checkpoint cannot commit';
    END IF;
    RETURN NEW;
END;
\$\$;
CREATE TRIGGER e2e_block_trade_checkpoint BEFORE UPDATE ON trade_outbox
    FOR EACH ROW EXECUTE FUNCTION e2e_block_trade_checkpoint();
COMMIT;
SQL
checkpoint_blocked=true
node scripts/watch-trade.mjs "${MARKET_DATA_WS_URL:-ws://localhost:8086/ws/market-data}" \
  "$trade_id" "$work/ready" "$work/delivered" "$work/replay-gate" &
watch_pid=$!
wait_for test -f "$work/ready"

docker compose start kafka
broker_stopped=false
wait_for test -s "$work/delivered"
test "$(sql <<< "SELECT published_at IS NULL FROM trade_outbox WHERE trade_id = '$trade_id'")" = t
docker compose stop order-service
order_stopped=true
unblock_checkpoint
checkpoint_blocked=false
touch "$work/replay-gate"
docker compose start order-service
order_stopped=false
wait_for ready
published() {
  [[ $(sql <<< "SELECT published_at IS NOT NULL FROM trade_outbox WHERE trade_id = '$trade_id'") == t ]]
}
wait_for published
wait "$watch_pid"
watch_pid=
place 200 "$buyer_token" shared-key "$bid"
test "$(field orderId)" = "$buy_id"
place 400 "$buyer_token" shared-key '{"market":"BTC-NGN","side":"BUY","type":"LIMIT","price":"50000000","quantity":"0.25"}'
test "$(sql <<< "SELECT count(*) FROM trading_fills WHERE buy_order_id = '$buy_id'")" = 1
place 400 "$seller_token" risk-price-band '{"market":"BTC-NGN","side":"SELL","type":"LIMIT","price":"40000000","quantity":"1"}'
test "$(field error)" = "Market halted or order price exceeds the 10% price band"
place 400 "$seller_token" risk-notional '{"market":"BTC-NGN","side":"SELL","type":"LIMIT","price":"51000000","quantity":"2"}'
test "$(field error)" = "Order notional exceeds 100,000,000 NGN"

provider_quote_available() {
  route_preview 51000000 1 | python3 -c 'import json, sys; p=json.load(sys.stdin); sys.exit(not (p["venue"] == "MOCK_PROVIDER" and 0 < p["price"] <= 51000000))'
}
wait_for provider_quote_available

# Race a cancellation against a crossing IOC. Either ordering must conserve funds.
race_buyer=$(python3 -c 'import uuid; print(uuid.uuid4())')
race_token=$(token "$race_buyer")
sql <<SQL
BEGIN;
INSERT INTO wallet_opening_balances VALUES ('$race_buyer','NGN',49000000);
INSERT INTO wallets (user_id, asset, balance) VALUES ('$race_buyer','NGN',49000000);
COMMIT;
SQL
place 201 "$seller_token" race-sell '{"market":"BTC-NGN","side":"SELL","type":"LIMIT","price":"49000000","quantity":"1"}'
race_ask=$(field orderId)
curl -fsS --max-time 10 -X DELETE "$BASE_URL/$race_ask" \
  -H "Authorization: Bearer $seller_token" > "$work/a" &
cancel_pid=$!
place 201 "$race_token" race-buy '{"market":"BTC-NGN","side":"BUY","type":"MARKET","price":"49000000","quantity":"1"}'
wait "$cancel_pid"
race_bid=$(field orderId)
race_status=$(field status)
ask_status=$(python3 -c 'import json, sys; print(json.load(sys.stdin)["status"])' < "$work/a")
trade_count=$(sql <<< "SELECT count(*) FROM trading_fills WHERE buy_order_id = '$race_bid' AND sell_order_id = '$race_ask'")
case "$ask_status/$race_status/$trade_count" in
  FILLED/FILLED/1|CANCELLED/CANCELLED/0) ;;
  *) echo "Inconsistent cancellation race: $ask_status/$race_status/$trade_count" >&2; exit 1 ;;
esac
test "$(sql <<< 'SELECT count(*) FROM trading_discrepancies')" = 0
echo "PASS: ownership, risk, quote routing and outage fallback, reservations, idempotency, cancellation race, reconciliation, Kafka checkpoint replay, and WebSocket delivery."
