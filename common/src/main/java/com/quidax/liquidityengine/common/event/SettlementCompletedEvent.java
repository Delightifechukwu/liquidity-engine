package com.quidax.liquidityengine.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by execution-settlement once both legs of a trade have been posted to the
 * double-entry ledger. Consumed by reconciliation-job and order-service (to flip order
 * status to FILLED/PARTIALLY_FILLED).
 */
public record SettlementCompletedEvent(
        UUID eventId,
        UUID tradeId,
        UUID buyOrderId,
        UUID sellOrderId,
        BigDecimal settledQuantity,
        BigDecimal settledPrice,
        Instant settledAt
) {
}
