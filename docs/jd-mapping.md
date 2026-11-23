# Quidax Trading & Liquidity role mapping

| Role area | What to review | Scope |
|---|---|---|
| Java and Spring | Order API, OAuth2 resource server, JPA entities, JDBC updates, Flyway and PostgreSQL transaction boundaries | Java 21, Spring Boot, signed demo JWTs with issuer, audience, and order ownership enforcement |
| Trading lifecycle | Price-time matching queries, order status transitions, reservation and release | Single BTC–NGN market. Limit and price-protected market orders. Stop orders are rejected |
| Financial correctness | Fill, wallet, posting, and outbox writes in one transaction | Fixed price tick and quantity scale, user wallets, four postings per fill, opening-balance and reservation reconciliation |
| Failure and concurrency | Compose smoke test, market row lock, stable outbox event identity | Tests concurrent replay, competing reservations, a cancel-versus-fill race, restart during a broker outage, and WebSocket replay after an outbox checkpoint fails |
| Liquidity and risk | Buy route preview, mock quote adapter, transactional pre-trade controls | Indicative quote selection uses size, price, expiry, and provider status. Venue execution stays internal |
| Performance and operations | Concurrent HTTP benchmark, reconciliation and recovery commands, CI | Local measurements, Docker Compose, Kafka, and PostgreSQL. Cloud deployment and production SLOs are outside the showcase |

Production ownership, team mentoring, and cross-functional incident work are topics for the application and interview. The repository is evidence of implementation decisions and the scenarios covered by its tests.
