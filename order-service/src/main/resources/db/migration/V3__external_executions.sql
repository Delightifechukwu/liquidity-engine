-- Explicit opening inventory for the simulated settlement counterparty.
INSERT INTO wallet_opening_balances VALUES ('00000000-0000-0000-0000-000000000001', 'BTC', 100);
INSERT INTO wallets (user_id, asset, balance)
VALUES ('00000000-0000-0000-0000-000000000001', 'BTC', 100);

CREATE TABLE external_executions (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    counterparty_order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    trade_id UUID UNIQUE REFERENCES trading_fills(id)
);
CREATE INDEX external_executions_pending
    ON external_executions(next_attempt_at, order_id) WHERE completed_at IS NULL;
