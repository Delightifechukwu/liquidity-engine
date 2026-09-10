# JD → Project mapping

Direct mapping from the Quidax "Senior Software Engineer – Java, Trading & Liquidity" job
description to this project, section by section.

## What You'll Be Owning

| JD line | Where it's demonstrated |
|---|---|
| Design, build, deploy, operate scalable Java services powering trading, pricing, execution, liquidity | All 8 modules — independently deployable Spring Boot services |
| Own critical backend services end to end | Each module: entity → repository → service → controller/listener, with its own Flyway migrations and tests |
| Reliable under heavy traffic, partial failures, changing conditions | `OrderBook`'s concurrency test; `execution-settlement`'s DLT/retry config; `liquidity-provider-adapter`'s circuit breaker |
| Order management, execution, pricing, liquidity | `order-service` + `matching-engine` + `liquidity-provider-adapter` |
| Integrations with exchanges, liquidity providers | `liquidity-provider-adapter` (mock external LP + Resilience4j) |
| Execution quality: pricing accuracy, spreads, fill rates, slippage | `OrderBook.spread()`; fill price always taken from the resting order (documented in `OrderBookTest`) |
| Order lifecycle: validation, routing, execution, settlement, reconciliation, cancellations, retries, recovery | `order-service` (validation, cancellation) → `matching-engine` (routing/execution) → `execution-settlement` (settlement, retries via DLT) → `reconciliation-job` (reconciliation) |
| Financial correctness: precision, idempotency, consistency, auditability | `Money` (fixed BigDecimal scale), idempotency keys throughout, double-entry ledger, immutable `ledger_entries` audit trail |
| Correct under concurrency, retries, duplicate events, eventual consistency | See README "core engineering problem" section — 3 dedicated tests |
| Observability, resilience, throughput, operational readiness | OpenTelemetry tracing wired into `order-service`/`matching-engine`/`execution-settlement`; actuator health/metrics on every module |
| RCA / preventive improvements | `reconciliation-job`'s stuck-trade detector exists specifically to surface silent settlement crashes before they become incidents |
| Testing: unit, integration, contract, performance, resilience | Unit (`OrderBookTest`), concurrency/resilience (`concurrentSubmissions...` test), idempotency/contract-adjacent (`MatchingServiceDuplicateEventTest`, `SettlementSagaIdempotencyTest`) |
| Architecture/design participation, code review, mentoring | Not something a solo repo can demonstrate directly — this is where the ActiveEdge Technologies experience (mentored 2 junior engineers) does the talking |

## Trading & Liquidity Knowledge

| Concept | Where |
|---|---|
| Order books and matching engines | `matching-engine` module, `OrderBook.java` |
| Market, limit, stop orders | `OrderType` enum; `OrderBook.submit()` handles MARKET (no resting) vs LIMIT (rests remainder); STOP order validation in `OrderService` (trigger logic marked as a documented extension point) |
| Bid/ask pricing, spreads, liquidity, slippage | `OrderBook.bestBid()/bestAsk()/spread()`; fills always priced at the resting order (standard slippage-for-the-taker behaviour) |
| Order routing and execution | `matching-engine`'s Kafka consumption + book submission path |
| Market data and pricing feeds | `market-data-service` |
| Exchange and liquidity-provider integrations | `liquidity-provider-adapter` |
| Settlement, reconciliation, balances, positions | `execution-settlement` + `reconciliation-job` |
| Decimal precision and financial calculations | `Money` value type, `NUMERIC(38,8)` columns throughout |
| Risk controls and abnormal market behaviour | `risk-service` (notional cap, price-deviation check, volatility kill-switch) |

## Honest gap

Kubernetes/cloud deployment is in the JD's technical skills list but isn't demonstrated by
this repo (Docker Compose only, for a local demo). That experience comes from AWS/EKS work
on other production engagements — see resume.
