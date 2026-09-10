CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(128) NOT NULL,
    user_id             UUID NOT NULL,
    market              VARCHAR(20) NOT NULL,
    side                VARCHAR(4)  NOT NULL,
    type                VARCHAR(10) NOT NULL,
    price               NUMERIC(38, 8),
    stop_price          NUMERIC(38, 8),
    quantity            NUMERIC(38, 8) NOT NULL,
    filled_quantity     NUMERIC(38, 8) NOT NULL DEFAULT 0,
    status              VARCHAR(24) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

-- The single most important constraint in this service: two requests with the same
-- idempotency key for the same user must never create two orders. A retried "place order"
-- call (client timeout, network blip) hits this constraint and the service returns the
-- original order instead of creating a duplicate.
CREATE UNIQUE INDEX uq_orders_user_idempotency_key ON orders (user_id, idempotency_key);

CREATE INDEX idx_orders_market_status ON orders (market, status);
CREATE INDEX idx_orders_user_id ON orders (user_id);
