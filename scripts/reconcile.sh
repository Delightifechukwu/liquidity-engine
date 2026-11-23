#!/usr/bin/env bash
set -euo pipefail
rows=$(docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -At -U liquidity -d order_service \
  -c 'SELECT kind, reference FROM trading_discrepancies ORDER BY kind, reference')
if [[ -n "$rows" ]]; then
  printf '%s\n' "$rows" >&2
  exit 1
fi
echo "PASS: fills, postings, wallet balances and reservations reconcile."

local_report=$(docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -At -U liquidity -d order_service <<'SQL'
SELECT e.order_id, o.price, o.quantity, e.completed_at IS NOT NULL,
       f.id, f.price, f.quantity
FROM external_executions e
JOIN orders o ON o.id = e.order_id
LEFT JOIN trading_fills f ON f.id = e.trade_id
ORDER BY e.order_id;
SQL
)
provider_report=$(docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -At -U liquidity -d liquidity_engine <<'SQL'
SELECT client_order_id, max_price, requested_quantity, true, fill_id, fill_price, fill_quantity
FROM simulated_provider.provider_executions
ORDER BY client_order_id;
SQL
)
if ! diff -u <(printf '%s\n' "$provider_report") <(printf '%s\n' "$local_report"); then
  echo "Discrepancies between provider execution reports and local settlement." >&2
  exit 1
fi
echo "PASS: external executions reconcile with provider reports."
