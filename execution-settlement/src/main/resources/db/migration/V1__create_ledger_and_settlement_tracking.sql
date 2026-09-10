-- Double-entry ledger: every settlement writes exactly two rows (a debit and a credit) that
-- must sum to zero for a given trade. This is the core financial-correctness mechanism —
-- an accounting bug shows up immediately as a trade whose entries don't net to zero, instead
-- of silently corrupting a running balance somewhere.
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    trade_id        UUID NOT NULL,
    account_id      UUID NOT NULL,
    entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    asset           VARCHAR(10) NOT NULL,
    amount          NUMERIC(38, 8) NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_trade_id ON ledger_entries (trade_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);

-- account_balances is a derived/materialized view maintained transactionally alongside
-- ledger_entries, rather than computed by summing history on every read — the ledger is the
-- source of truth and audit trail; this table is the fast path for "what can this account
-- spend right now", updated in the same transaction as the entries that justify it.
CREATE TABLE account_balances (
    account_id      UUID NOT NULL,
    asset           VARCHAR(10) NOT NULL,
    balance         NUMERIC(38, 8) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, asset)
);

-- Idempotency for the settlement saga: one row per trade, inserted before any ledger writes
-- happen. A redelivered TradeExecutedEvent for a trade_id already present here is a no-op —
-- this is what took duplicate-settlement incidents to zero (see README "Impact Highlights").
CREATE TABLE processed_trades (
    trade_id        UUID PRIMARY KEY,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    attempt_count   INT NOT NULL DEFAULT 1,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
