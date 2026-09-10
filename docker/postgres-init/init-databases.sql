-- order-service owns its own database, separate from liquidity_engine (which
-- execution-settlement and reconciliation-job share for ledger/settlement data).
-- Both services independently run Flyway migrations named V1, which collide in a
-- shared flyway_schema_history table if they point at the same database.
CREATE DATABASE order_service;
GRANT ALL PRIVILEGES ON DATABASE order_service TO liquidity;
