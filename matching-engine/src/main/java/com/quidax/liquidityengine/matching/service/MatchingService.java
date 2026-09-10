package com.quidax.liquidityengine.matching.service;

import com.quidax.liquidityengine.common.event.OrderCreatedEvent;
import com.quidax.liquidityengine.common.event.TradeExecutedEvent;
import com.quidax.liquidityengine.common.model.Money;
import com.quidax.liquidityengine.common.model.Order;
import com.quidax.liquidityengine.common.model.Trade;
import com.quidax.liquidityengine.common.util.Topics;
import com.quidax.liquidityengine.matching.engine.MatchResult;
import com.quidax.liquidityengine.matching.engine.OrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Kafka events to the in-memory OrderBook and publishes resulting trades.
 * Deduplication (see `seenIdempotencyKeys`) protects against Kafka's at-least-once delivery:
 * a redelivered OrderCreatedEvent for an order already applied to the book must be a no-op,
 * not a second insertion of the same liquidity.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final OrderBookRegistry registry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Bounded in a real system (e.g. Redis with TTL); an in-memory Set is enough to
    // demonstrate the de-duplication pattern for this project's scope.
    private final Set<String> seenIdempotencyKeys = ConcurrentHashMap.newKeySet();

    public MatchingService(OrderBookRegistry registry, KafkaTemplate<String, Object> kafkaTemplate) {
        this.registry = registry;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void handleOrderCreated(OrderCreatedEvent event) {
        if (!seenIdempotencyKeys.add(event.idempotencyKey())) {
            log.info("Duplicate OrderCreatedEvent for idempotencyKey={} — ignoring (already matched)",
                    event.idempotencyKey());
            return;
        }

        Order order = event.order();
        OrderBook book = registry.forMarket(order.market());

        BigDecimal limitPrice = order.price() != null ? order.price().value() : null;
        List<MatchResult> fills = book.submit(order.orderId(), order.side(), order.type(),
                limitPrice, order.quantity().value());

        for (MatchResult fill : fills) {
            Trade trade = toTrade(order, fill);
            TradeExecutedEvent tradeEvent = TradeExecutedEvent.of(trade);
            // Keyed by tradeId so execution-settlement and market-data-service both see a
            // stable per-trade partition — required for their own idempotency checks
            // ("have I settled this tradeId already?") to be reliable.
            kafkaTemplate.send(Topics.TRADES_EXECUTED, trade.tradeId().toString(), tradeEvent);
        }

        log.info("Order {} on {} produced {} fill(s)", order.orderId(), order.market(), fills.size());
    }

    private Trade toTrade(Order incomingOrder, MatchResult fill) {
        boolean incomingIsBuy = incomingOrder.side().name().equals("BUY");
        return new Trade(
                fill.tradeId(),
                incomingOrder.market(),
                incomingIsBuy ? incomingOrder.orderId() : fill.restingOrderId(),
                incomingIsBuy ? fill.restingOrderId() : incomingOrder.orderId(),
                Money.of(fill.price()),
                Money.of(fill.quantity()),
                Money.ZERO, // fee calculation delegated to execution-settlement, which owns the fee schedule
                Money.ZERO,
                fill.matchedAt()
        );
    }
}
