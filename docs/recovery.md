# Recovery

## Broker unavailable

Trades continue settling in PostgreSQL. The relay leaves unpublished rows pending and retries when Kafka returns. To inspect the backlog:

```sh
docker compose exec -T postgres psql -X -U liquidity -d order_service -c \
  'SELECT count(*) AS pending, min(f.executed_at) AS oldest_fill
   FROM trade_outbox p JOIN trading_fills f ON f.id = p.trade_id WHERE p.published_at IS NULL'
docker compose logs --tail=150 kafka order-service market-data-service
```

After restoring Kafka, check that the backlog drains. A repeated event carries the same trade ID. Database settlement has already occurred, so event recovery uses the outbox. The default trading path has no settlement dead-letter queue.

## Reconciliation discrepancy

Run `./scripts/reconcile.sh`. A `POSTING` or `UNEXPECTED_POSTING` result identifies a trade to inspect in `trading_fills` and `trading_postings`. A `WALLET` result identifies a user and asset. Compare the wallet with its opening balance, signed trade postings, and remaining reservations. Preserve the records for investigation.

To halt order acceptance while investigating, update the market flag through a privileged PostgreSQL session:

```sql
UPDATE trading_market SET halted = true WHERE market = 'BTC-NGN';
```

Resting internal orders can still be cancelled. Pending external orders retain their reservations until the provider reports a terminal outcome. After resolving and reconciling the discrepancy, clear the flag to reopen order acceptance.

## External execution pending

The buyer and simulated counterparty retain their reservations while the provider outcome is unknown. The relay retries lookup using the persisted engine order ID. If lookup returns HTTP 404, the relay can submit again using that same ID and payload. The provider stores one report per client order ID.

```sh
docker compose exec -T postgres psql -X -U liquidity -d order_service -c \
  'SELECT order_id, next_attempt_at FROM external_executions WHERE completed_at IS NULL'
docker compose logs --tail=150 order-service liquidity-provider-adapter
```

After pending work drains, run `./scripts/reconcile.sh`. It compares provider execution reports with local order and fill records in addition to checking ledger postings. Pending intents and missing or mismatched reports fail that check.

## Upgrading the earlier demo

Flyway retains the earlier order records and cancels legacy active orders, which have no wallet reservations. The old settlement database used order IDs as accounts and stays separate. For a fresh showcase run with no retained local data, start Compose under a new project name.
