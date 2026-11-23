-- Retire legacy resting orders, which have no wallet reservations.
UPDATE orders SET status = 'CANCELLED', updated_at = now(), version = version + 1
WHERE status IN ('OPEN', 'PARTIALLY_FILLED', 'PENDING_VALIDATION');

ALTER TABLE orders ADD COLUMN priority BIGSERIAL;
ALTER TABLE orders ADD COLUMN reserved NUMERIC(38,8) NOT NULL DEFAULT 0 CHECK (reserved >= 0);
CREATE INDEX orders_book_buy ON orders (market, price DESC, priority)
    WHERE side = 'BUY' AND type = 'LIMIT' AND status IN ('OPEN', 'PARTIALLY_FILLED');
CREATE INDEX orders_book_sell ON orders (market, price, priority)
    WHERE side = 'SELL' AND type = 'LIMIT' AND status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE TABLE trading_market (
    market VARCHAR(20) PRIMARY KEY,
    halted BOOLEAN NOT NULL DEFAULT FALSE,
    last_price NUMERIC(38,8)
);
INSERT INTO trading_market(market) VALUES ('BTC-NGN');

CREATE TABLE wallets (
    user_id UUID NOT NULL,
    asset VARCHAR(10) NOT NULL,
    balance NUMERIC(38,8) NOT NULL CHECK (balance >= 0),
    reserved NUMERIC(38,8) NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= balance),
    PRIMARY KEY (user_id, asset)
);

CREATE TABLE trading_fills (
    id UUID PRIMARY KEY,
    buy_order_id UUID NOT NULL REFERENCES orders(id),
    sell_order_id UUID NOT NULL REFERENCES orders(id),
    price NUMERIC(38,8) NOT NULL CHECK (price > 0),
    quantity NUMERIC(38,8) NOT NULL CHECK (quantity > 0),
    executed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trading_postings (
    trade_id UUID NOT NULL REFERENCES trading_fills(id),
    asset VARCHAR(10) NOT NULL,
    direction SMALLINT NOT NULL CHECK (direction IN (-1, 1)),
    user_id UUID NOT NULL,
    amount NUMERIC(38,8) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (trade_id, asset, direction),
    FOREIGN KEY (user_id, asset) REFERENCES wallets(user_id, asset)
);

CREATE TABLE trade_outbox (
    sequence BIGSERIAL PRIMARY KEY,
    trade_id UUID NOT NULL UNIQUE REFERENCES trading_fills(id),
    published_at TIMESTAMPTZ
);
CREATE INDEX trade_outbox_pending ON trade_outbox(sequence) WHERE published_at IS NULL;

-- Demo opening balances. Funding and the corresponding wallet credit are seeded together.
CREATE TABLE wallet_opening_balances (
    user_id UUID NOT NULL,
    asset VARCHAR(10) NOT NULL,
    amount NUMERIC(38,8) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (user_id, asset)
);

CREATE VIEW trading_discrepancies AS
WITH expected_postings AS (
    SELECT f.id, leg.asset, leg.direction, leg.user_id, leg.amount
    FROM trading_fills f
    JOIN orders b ON b.id = f.buy_order_id
    JOIN orders s ON s.id = f.sell_order_id
    CROSS JOIN LATERAL (VALUES
        ('BTC', -1, s.user_id, f.quantity),
        ('BTC',  1, b.user_id, f.quantity),
        ('NGN', -1, b.user_id, f.quantity * f.price),
        ('NGN',  1, s.user_id, f.quantity * f.price)
    ) leg(asset, direction, user_id, amount)
), net_postings AS (
    SELECT user_id, asset, sum(direction * amount) AS net
    FROM trading_postings GROUP BY user_id, asset
), reservations AS (
    SELECT user_id, CASE side WHEN 'BUY' THEN 'NGN' ELSE 'BTC' END AS asset, sum(reserved) AS amount
    FROM orders WHERE status IN ('OPEN', 'PARTIALLY_FILLED') GROUP BY 1, 2
), wallet_keys AS (
    SELECT user_id, asset FROM wallets UNION SELECT user_id, asset FROM wallet_opening_balances
    UNION SELECT user_id, asset FROM reservations
)
SELECT 'POSTING' AS kind, e.id::text AS reference
FROM expected_postings e LEFT JOIN trading_postings p
    ON p.trade_id = e.id AND p.asset = e.asset AND p.direction = e.direction
WHERE p.user_id IS DISTINCT FROM e.user_id OR p.amount IS DISTINCT FROM e.amount
UNION ALL
SELECT 'UNEXPECTED_POSTING', p.trade_id::text
FROM trading_postings p LEFT JOIN expected_postings e
    ON p.trade_id = e.id AND p.asset = e.asset AND p.direction = e.direction
WHERE e.id IS NULL
UNION ALL
SELECT 'WALLET', k.user_id::text || '/' || k.asset
FROM wallet_keys k
LEFT JOIN wallets w USING (user_id, asset)
LEFT JOIN wallet_opening_balances o USING (user_id, asset)
LEFT JOIN net_postings n USING (user_id, asset)
LEFT JOIN reservations r USING (user_id, asset)
WHERE w.balance IS DISTINCT FROM coalesce(o.amount, 0) + coalesce(n.net, 0)
   OR w.reserved IS DISTINCT FROM coalesce(r.amount, 0);
