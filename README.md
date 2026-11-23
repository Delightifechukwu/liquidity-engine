# Liquidity Engine

A Java 21 and Spring Boot simulation of a BTC–NGN exchange. It shows how I apply experience with payments settlement, multi-bank ledgers, reconciliation, and ISO 8583 switching to a trading problem: keeping orders and balances correct through concurrent requests, retries, and broker outages.

## Run the demo

Requires Docker Compose, Bash, curl, Python 3, and Node.js 22+. From a clean checkout:

```sh
docker compose up -d --build
./scripts/e2e-test.sh
./scripts/reconcile.sh
```

The smoke test creates two funded demo users and a resting sell order. It verifies that a different user cannot read or cancel that order. It stops Kafka, restarts the order service, and submits the same buy request concurrently. The buy fills once, the seller cancels the remainder, and the ledger and wallet assertions pass. After Kafka returns, the test forces the publication checkpoint to roll back, restarts the relay, and checks that a real WebSocket client receives the same trade ID again without a second settlement. It also checks risk limits, provider outage fallback, and a cancellation racing with an immediate-or-cancel buy. Run this scenario on a dedicated local stack because it interrupts services and temporarily injects a PostgreSQL failure.

The order API listens on `127.0.0.1:8081`. The mock identity provider signs tokens on the private Compose network. The token subject supplies the wallet identity, and the API validates the issuer and audience. The script seeds opening balances directly in PostgreSQL as a stand-in for a deposit flow.

## Order and trade flow

1. **Accept.** The order service checks the token, validates the request, and takes the PostgreSQL market row lock. A retry with the same user, key, and payload returns the existing order. Reusing the key with a different payload fails.
2. **Reserve.** A buy reserves NGN at its maximum price and a sell reserves BTC. A notional cap, price band against the last committed fill, and a persisted market halt flag gate new orders. Prices use a one-NGN tick and BTC quantity supports eight decimals. This lets every supported price × quantity product settle exactly at eight decimals.
3. **Match and settle.** The database-backed book selects the best eligible price, with FIFO at equal prices. Each fill executes at the resting order price and posts two entries per asset. A fill applies to both orders and consumes the corresponding reservation in the same transaction. Price improvement releases excess buy reservation. Cancellation releases the unfilled reservation. A price-protected market order cancels any remainder. If an incoming order reaches its own resting order, it cancels the incoming remainder.
4. **Publish.** The transaction also writes one outbox row per fill. A relay publishes to Kafka using the persisted trade ID and marks the row published after broker acknowledgement. If a crash occurs between those steps, the same trade event can appear again. Kafka delivery has no authority to debit wallets. Market data broadcasts committed trades over WebSocket as a best-effort feed.
5. **Reconcile.** The SQL reconciliation checks the expected accounts and amounts for each persisted fill, and compares wallet balances and reservations with their opening balances, postings, and open orders.

The market lock gives price-time priority, prevents cancellation from racing a fill, and makes wallet updates straightforward to reason about. It also serializes all orders in the market. The default stack is designed to demonstrate that trade-off and the recovery behavior. It runs the order service, market-data service, and mock liquidity adapter alongside its infrastructure. The earlier in-memory Kafka matcher, async settlement, risk service, and reconciliation job remain available under the Compose `legacy` profile. The audited path uses a separate database-backed book and emits `trades.settled` instead of feeding the older settlement consumer.

## Liquidity and execution scope

The authenticated `GET /api/v1/routes/buy-preview?maxPrice=50000000&quantity=0.1` endpoint compares an eligible internal price level with a mock provider offer. It checks price, size, expiry, and degraded status. It falls back to internal liquidity when the provider is unavailable. This is an indicative, single-level preview. Set `MOCK_LP_FAILURE_RATE` before starting Compose to inject mock provider errors. Orders execute on the internal book. Provider execution, fees, deposits and withdrawals, and stop triggers are outside the demo’s scope. Stop orders are rejected explicitly.

## Verify and measure

```sh
mvn --batch-mode clean verify
python3 scripts/benchmark.py --orders 200 --workers 16
```

The benchmark seeds separate maker and taker wallets, places a resting book, and measures concurrent taker submissions through HTTP and the database. It reports p50, p95, p99, and orders per second, then verifies fill count, FIFO, and ledger reconciliation. Use a quiet demo stack for repeatable runs. GitHub Actions runs the Java tests and Compose smoke test from a clean checkout.

One local run on an ARM64 Docker Desktop VM with 12 logical CPUs and 7.75 GiB RAM processed 200 taker orders at 16 workers. Throughput was **260.4 orders/s**, with **55.0 ms p50**, **96.7 ms p95**, and **120.0 ms p99**. This measures HTTP submission through committed settlement. Kafka delivery runs asynchronously and is excluded from those timings.

See [recovery notes](docs/recovery.md) for operational queries and [the Quidax role mapping](docs/jd-mapping.md) for the scope of the showcase.
