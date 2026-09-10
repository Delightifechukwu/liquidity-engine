package com.quidax.liquidityengine.matching.engine;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * All resting orders at a single price, in strict time priority (FIFO). A plain ArrayDeque
 * is safe here because every mutation happens while the OrderBook's per-market lock is held —
 * see OrderBook's locking discipline.
 */
final class PriceLevel {

    private final BigDecimal price;
    private final Deque<RestingOrder> orders = new ArrayDeque<>();

    PriceLevel(BigDecimal price) {
        this.price = price;
    }

    BigDecimal price() { return price; }

    void addLast(RestingOrder order) { orders.addLast(order); }

    RestingOrder peekFirst() { return orders.peekFirst(); }

    void removeFirstIfFullyFilled() {
        RestingOrder first = orders.peekFirst();
        if (first != null && first.isFullyFilled()) {
            orders.pollFirst();
        }
    }

    void remove(RestingOrder order) { orders.remove(order); }

    RestingOrder findById(java.util.UUID orderId) {
        for (RestingOrder o : orders) {
            if (o.orderId().equals(orderId)) return o;
        }
        return null;
    }

    boolean isEmpty() { return orders.isEmpty(); }

    BigDecimal totalQuantity() {
        return orders.stream().map(RestingOrder::remainingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    int orderCount() { return orders.size(); }
}
