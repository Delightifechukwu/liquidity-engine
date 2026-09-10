package com.quidax.liquidityengine.common.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable order snapshot shared across services via events. The mutable, persisted
 * version of an order lives in order-service's own entity — this is the wire representation.
 */
public record Order(
        UUID orderId,
        String idempotencyKey,
        UUID userId,
        String market,          // e.g. "BTC-NGN"
        OrderSide side,
        OrderType type,
        Money price,            // null for MARKET orders
        Money stopPrice,        // set only for STOP orders
        Money quantity,
        Money filledQuantity,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public Money remainingQuantity() {
        return quantity.subtract(filledQuantity);
    }

    public boolean isFullyFilled() {
        return remainingQuantity().isZero() || remainingQuantity().isNegative();
    }
}
