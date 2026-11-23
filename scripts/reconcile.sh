#!/usr/bin/env bash
set -euo pipefail
rows=$(docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -At -U liquidity -d order_service \
  -c 'SELECT kind, reference FROM trading_discrepancies ORDER BY kind, reference')
if [[ -n "$rows" ]]; then
  printf '%s\n' "$rows" >&2
  exit 1
fi
echo "PASS: fills, postings, wallet balances and reservations reconcile."
