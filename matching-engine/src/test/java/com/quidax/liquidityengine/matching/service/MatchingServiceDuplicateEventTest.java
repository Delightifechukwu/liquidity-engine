package com.quidax.liquidityengine.matching.service;

import com.quidax.liquidityengine.common.event.OrderCreatedEvent;
import com.quidax.liquidityengine.common.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * THE demo scenario referenced in the project README: Kafka's at-least-once delivery means
 * a broker restart, consumer rebalance, or producer retry can redeliver the exact same
 * OrderCreatedEvent. Without deduplication, replaying it would submit the same liquidity to
 * the book twice — a phantom double-fill against a counterparty who only ever intended to
 * trade once. This test proves that redelivery is a safe no-op.
 */
class MatchingServiceDuplicateEventTest {

    @Test
    void redeliveredOrderCreatedEventDoesNotProduceASecondFill() {
        OrderBookRegistry registry = new OrderBookRegistry();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MatchingService matchingService = new MatchingService(registry, kafkaTemplate);

        // Seed the book with resting sell liquidity so the incoming buy has something to match.
        UUID sellerId = UUID.randomUUID();
        registry.forMarket("BTC-NGN").submit(sellerId, OrderSide.SELL,
                OrderType.LIMIT, new BigDecimal("50000000"), new BigDecimal("1.0"));

        Order incomingBuy = new Order(
                UUID.randomUUID(), "idem-key-abc123", UUID.randomUUID(), "BTC-NGN",
                OrderSide.BUY, OrderType.LIMIT, Money.of("50000000"), null,
                Money.of("1.0"), Money.ZERO, OrderStatus.OPEN, Instant.now(), Instant.now()
        );
        OrderCreatedEvent event = OrderCreatedEvent.of(incomingBuy);

        // First delivery: matches and fills exactly once.
        matchingService.handleOrderCreated(event);

        // Simulate Kafka redelivering the SAME event — same idempotencyKey, same payload —
        // which happens naturally under at-least-once semantics after a rebalance or a
        // consumer crash before the offset commit landed.
        matchingService.handleOrderCreated(event);
        matchingService.handleOrderCreated(event); // and again, for good measure

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(1)).send(eq("trades.executed"), anyString(), published.capture());

        // Exactly one TradeExecutedEvent was published across three deliveries of the same event.
        assertThat(published.getAllValues()).hasSize(1);

        // And the book reflects exactly one fill of 1.0 — not two, not three.
        assertThat(registry.forMarket("BTC-NGN").bestAsk()).isEmpty();
    }
}
