package com.quidax.liquidityengine.common.event;

import com.quidax.liquidityengine.common.model.Order;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by order-service after an order passes pre-trade validation & risk checks.
 * Consumed by matching-engine. Carries the idempotency key so downstream consumers can
 * de-duplicate if the same event is redelivered (at-least-once Kafka semantics).
 */
public record OrderCreatedEvent(
        UUID eventId,
        String idempotencyKey,
        Order order,
        Instant occurredAt
) {
    public static OrderCreatedEvent of(Order order) {
        return new OrderCreatedEvent(UUID.randomUUID(), order.idempotencyKey(), order, Instant.now());
    }
}
