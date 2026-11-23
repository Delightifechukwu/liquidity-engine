package com.quidax.liquidityengine.common.util;

/**
 * Central registry of Kafka topic names. Kept in `common` so every service references
 * the same constant instead of hardcoding topic strings — a mistyped topic name is a
 * silent, hard-to-debug outage in an event-driven system.
 */
public final class Topics {
    private Topics() {}

    public static final String ORDERS_CREATED = "orders.created";
    public static final String TRADES_SETTLED = "trades.settled";
    public static final String ORDERS_CANCELLED = "orders.cancelled";
    public static final String TRADES_EXECUTED = "trades.executed";
    public static final String SETTLEMENTS_COMPLETED = "settlements.completed";
    public static final String SETTLEMENTS_FAILED = "settlements.failed";
    public static final String SETTLEMENTS_DLT = "settlements.completed.DLT";
    public static final String MARKET_DATA_TICKS = "market-data.ticks";
}
