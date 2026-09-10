package com.quidax.liquidityengine.matching.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One fill produced by matching an incoming order against a resting order. A single incoming
 * order can produce zero, one, or many MatchResults (walking multiple price levels).
 */
public record MatchResult(
        UUID tradeId,
        UUID incomingOrderId,
        UUID restingOrderId,
        BigDecimal price,      // always the resting order's price — the resting side sets the price
        BigDecimal quantity,
        Instant matchedAt
) {
    static MatchResult of(UUID incomingOrderId, RestingOrder resting, BigDecimal fillQty) {
        return new MatchResult(UUID.randomUUID(), incomingOrderId, resting.orderId(),
                resting.price(), fillQty, Instant.now());
    }
}
