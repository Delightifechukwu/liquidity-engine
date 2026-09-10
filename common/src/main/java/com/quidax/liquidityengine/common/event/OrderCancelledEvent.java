package com.quidax.liquidityengine.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by order-service when a user or the risk-service kill-switch cancels a
 * resting order. Consumed by matching-engine to remove the order from the book.
 */
public record OrderCancelledEvent(
        UUID eventId,
        UUID orderId,
        String reason,
        Instant occurredAt
) {
}
