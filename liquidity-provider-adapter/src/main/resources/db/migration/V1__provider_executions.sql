CREATE TABLE provider_executions (
    client_order_id UUID PRIMARY KEY,
    max_price NUMERIC(38,8) NOT NULL,
    requested_quantity NUMERIC(38,8) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    fill_id UUID UNIQUE,
    fill_price NUMERIC(38,8),
    fill_quantity NUMERIC(38,8),
    submissions INT NOT NULL DEFAULT 1
);
