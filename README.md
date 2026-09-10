# liquidity-engine

A simulated crypto trading & liquidity engine — order lifecycle, matching, execution,
settlement, market data, external liquidity-provider integration, risk controls, and
reconciliation — built as a focused demonstration of the engineering surface area behind a
real exchange trading core.

This project was built while preparing for a Senior Software Engineer (Trading & Liquidity)
role. Every module below maps directly onto a specific requirement from that job description;
see [`docs/jd-mapping.md`](docs/jd-mapping.md) for the full breakdown.

## Why this exists

Most of my production experience is in **payments settlement** — multi-bank ledgers,
reconciliation, ISO 8583 switching. That's the same underlying discipline as **trading**
(distributed systems that must stay correct under concurrency, retries, and partial failure),
applied to a different domain. I built this to prove the concepts transfer: order books,
matching, slippage, and market-maker-style liquidity routing — not just relabel payments work.

## Architecture

```
                    ┌──────────────┐
   HTTP ──────────► │ order-service│──────┐
                    └──────────────┘      │ OrderCreatedEvent (Kafka)
                           │ risk check           ▼
                           ▼              ┌────────────────┐
                    ┌──────────────┐      │ matching-engine │
                    │ risk-service │      │  (order book)   │
                    └──────────────┘      └────────────────┘
                                                   │ TradeExecutedEvent (Kafka)
                           ┌───────────────────────┼───────────────────────┐
                           ▼                       ▼                       ▼
                ┌─────────────────────┐  ┌──────────────────┐  ┌───────────────────┐
                │ execution-settlement│  │market-data-service│  │ (future: fee svc)  │
                │  (double-entry      │  │ (WebSocket ticks) │  └───────────────────┘
                │   ledger + saga)    │  └──────────────────┘
                └─────────────────────┘
                           │ SettlementCompletedEvent
                           ▼
                ┌─────────────────────┐        ┌──────────────────────────┐
                │  reconciliation-job │◄───────►│liquidity-provider-adapter│
                │ (nightly ledger     │         │ (circuit breaker + retry │
                │  balance check)     │         │  against external LP)   │
                └─────────────────────┘         └──────────────────────────┘
```

Eight independently deployable Spring Boot services, one Kafka bus, one Postgres database
(shared schema across order-service, execution-settlement, and reconciliation-job — a
deliberate simplification; a production split would give execution-settlement its own
schema and expose reconciliation reads via API instead of direct table access).

## Modules

| Module | Responsibility |
|---|---|
| `common` | Shared domain model (`Order`, `Trade`, `Money`), Kafka event contracts, topic registry |
| `order-service` | Order intake, validation, idempotent creation, cancellation |
| `matching-engine` | Price-time-priority order book, matching, fill generation |
| `execution-settlement` | Double-entry ledger, saga-style settlement, DLT + retry |
| `liquidity-provider-adapter` | Resilient integration with an external LP (circuit breaker/retry/timeout) |
| `risk-service` | Pre-trade notional/price checks, volatility kill-switch |
| `market-data-service` | Kafka → WebSocket trade-tick broadcast |
| `reconciliation-job` | Nightly (+ on-demand) ledger balance verification |

## The core engineering problem this project demonstrates

**Correctness under concurrency, retries, and duplicate events** — the exact language from
the JD's "Biggest Challenges" section. Two places this is proven with a test, not just a
claim:

1. **`matching-engine`**: `OrderBookTest.concurrentSubmissionsFromMultipleThreadsProduceNoDoubleFillOrLostQuantity`
   — 50 threads race to fill the same resting order; asserts the total filled quantity is
   exactly correct, no more, no less.

2. **`matching-engine`**: `MatchingServiceDuplicateEventTest.redeliveredOrderCreatedEventDoesNotProduceASecondFill`
   — simulates Kafka's at-least-once delivery redelivering the same event three times;
   asserts exactly one trade is produced, not three.

3. **`execution-settlement`**: `SettlementSagaIdempotencyTest` — same story at the settlement
   layer: a trade already marked `COMPLETED` in `processed_trades` short-circuits before any
   ledger write, so even if #2's dedup were somehow bypassed, settlement itself won't
   double-post.

Run them:
```bash
mvn -pl matching-engine,execution-settlement test
```

## Running locally

```bash
docker compose up --build
```

Brings up Kafka, Postgres, Redis, and all eight services. Then:

```bash
curl -X POST http://localhost:8081/api/v1/orders \
  -H "Idempotency-Key: demo-key-001" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "11111111-1111-1111-1111-111111111111",
    "market": "BTC-NGN",
    "side": "SELL",
    "type": "LIMIT",
    "price": "50000000",
    "quantity": "1.0"
  }'
```

Then place a matching BUY order and watch it fill — check the matching-engine logs for the
fill, and `execution-settlement` logs for the settlement.

To see the idempotency guarantee live: resend the **exact same** `place order` curl command
(same `Idempotency-Key`) — you'll get back the original order, not a second one.

## Design decisions worth discussing

- **Why a lock per `OrderBook` instead of lock-free structures?** Correctness first: matching
  is a multi-step, must-be-atomic operation (walk levels, mutate quantity, remove filled
  orders, insert remainder). A per-market `ReentrantLock` serializes access *within* a market
  while different markets run fully in parallel — the right granularity for this scale. See
  the comment block at the top of `OrderBook.java`.
- **Why pessimistic locking on account balances but optimistic (`@Version`) on orders?**
  Settlement contention on a hot account is common and expected; retry storms under optimistic
  locking would themselves become a throughput problem there. Order updates are lower-contention
  (one user's order, rarely raced), so optimistic locking's lower overhead wins.
- **Why is "saga" a single local transaction here, not a distributed saga?** All four ledger
  entries for a trade live in one database, so one `@Transactional` method gives real ACID
  guarantees — stronger than a distributed saga's eventual consistency. The saga *pattern*
  (idempotent steps, durable progress marker, safe replay) is preserved via `processed_trades`,
  which is what actually matters for surviving a mid-settlement crash.
- **Known simplification**: ledger entries are keyed by `accountId = orderId` rather than a
  proper user wallet/account model, since order-service doesn't model separate accounts in
  this scope. A real implementation would resolve `userId → walletAccountId` before posting.

## What's intentionally out of scope

- Kubernetes manifests / cloud deployment (demonstrated separately via AWS/EKS production
  experience, not re-built here)
- Authentication beyond JWT resource-server wiring on `order-service`
- A real matching-engine performance benchmark (this optimizes for correctness and
  readability of the concurrency story, not microsecond-level tuning)
- Multi-asset ledger beyond a simplified single quote-asset (NGN) model
