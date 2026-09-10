package com.quidax.liquidityengine.matching.engine;

import com.quidax.liquidityengine.common.model.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mutable, in-memory representation of an order sitting on the book. Deliberately NOT the
 * same type as the immutable `Order` record shared over Kafka — the book needs a mutable
 * remaining-quantity field that gets decremented in place as fills happen, without
 * re-publishing an event per partial decrement.
 */
public final class RestingOrder {

    private final UUID orderId;
    private final OrderSide side;
    private final BigDecimal price; // null only for an unfilled remainder of a MARKET order, which never rests
    private volatile BigDecimal remainingQuantity;
    private final Instant enteredAt;
    private final long sequence; // tie-breaker for strict time priority at the same price + same nanosecond

    public RestingOrder(UUID orderId, OrderSide side, BigDecimal price, BigDecimal quantity,
                         Instant enteredAt, long sequence) {
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.remainingQuantity = quantity;
        this.enteredAt = enteredAt;
        this.sequence = sequence;
    }

    public UUID orderId() { return orderId; }
    public OrderSide side() { return side; }
    public BigDecimal price() { return price; }
    public BigDecimal remainingQuantity() { return remainingQuantity; }
    public Instant enteredAt() { return enteredAt; }
    public long sequence() { return sequence; }

    public boolean isFullyFilled() {
        return remainingQuantity.signum() <= 0;
    }

    /** Reduces remaining quantity by the fill amount. Caller must hold the book's per-market lock. */
    void reduceBy(BigDecimal fillQty) {
        this.remainingQuantity = this.remainingQuantity.subtract(fillQty);
    }
}
