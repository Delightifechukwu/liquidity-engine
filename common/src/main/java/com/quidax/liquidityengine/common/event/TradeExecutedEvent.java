package com.quidax.liquidityengine.common.event;

import com.quidax.liquidityengine.common.model.Trade;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by matching-engine whenever a match occurs. Consumed by execution-settlement
 * (to update the ledger) and market-data-service (to broadcast the tick). The event key used
 * on the Kafka topic is trade.tradeId() — this guarantees per-trade ordering and lets consumers
 * detect exact-duplicate redelivery via a simple "have I processed this tradeId" check.
 */
public record TradeExecutedEvent(
        UUID eventId,
        Trade trade,
        Instant occurredAt
) {
    public static TradeExecutedEvent of(Trade trade) {
        return new TradeExecutedEvent(UUID.randomUUID(), trade, Instant.now());
    }
}
