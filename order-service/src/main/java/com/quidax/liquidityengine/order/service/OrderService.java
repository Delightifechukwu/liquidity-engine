package com.quidax.liquidityengine.order.service;

import com.quidax.liquidityengine.common.event.OrderCancelledEvent;
import com.quidax.liquidityengine.common.event.OrderCreatedEvent;
import com.quidax.liquidityengine.common.model.*;
import com.quidax.liquidityengine.common.util.Topics;
import com.quidax.liquidityengine.order.controller.PlaceOrderRequest;
import com.quidax.liquidityengine.order.entity.OrderEntity;
import com.quidax.liquidityengine.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Owns the order lifecycle up to the point of handoff to matching-engine: validation,
 * idempotent creation, and publishing OrderCreatedEvent. Everything downstream of "order
 * accepted into the book" belongs to matching-engine and execution-settlement.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderService(OrderRepository orderRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public record PlaceOrderResult(OrderEntity order, boolean wasAlreadyPresent) {}

    @Transactional
    public PlaceOrderResult placeOrder(String idempotencyKey, PlaceOrderRequest request) {
        // 1. Idempotency check FIRST — before any validation or side effects. If this exact
        //    request (same user + same key) already succeeded, we return that result rather
        //    than re-validating or re-publishing, which would double-count risk checks and
        //    could re-emit an event the matching engine has already consumed.
        var existing = orderRepository.findByUserIdAndIdempotencyKey(request.userId(), idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay for user={} key={} -> returning existing order={}",
                    request.userId(), idempotencyKey, existing.get().getId());
            return new PlaceOrderResult(existing.get(), true);
        }

        validate(request);

        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        OrderEntity entity = new OrderEntity(
                orderId, idempotencyKey, request.userId(), request.market(), request.side(),
                request.type(), request.price(), request.stopPrice(), request.quantity(),
                OrderStatus.OPEN, now, now
        );

        // Rely on the DB unique constraint (user_id, idempotency_key) as the final safety net
        // in case two concurrent requests race past the check above.
        OrderEntity saved = orderRepository.save(entity);

        Order domainOrder = toDomain(saved);
        OrderCreatedEvent event = OrderCreatedEvent.of(domainOrder);

        // Keyed by market so all orders for BTC-NGN land on the same partition and are
        // consumed in submission order by a single matching-engine instance for that market —
        // required for price-time priority to mean anything.
        kafkaTemplate.send(Topics.ORDERS_CREATED, request.market(), event);

        log.info("Order {} created and published for market {}", orderId, request.market());
        return new PlaceOrderResult(saved, false);
    }

    @Transactional(readOnly = true)
    public OrderEntity getOrder(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public OrderEntity cancelOrder(UUID orderId) {
        OrderEntity order = getOrder(orderId);
        order.cancel();
        OrderEntity saved = orderRepository.save(order);

        kafkaTemplate.send(Topics.ORDERS_CANCELLED, order.getId().toString(),
                new OrderCancelledEvent(UUID.randomUUID(), orderId, "USER_REQUESTED", Instant.now()));

        return saved;
    }

    private void validate(PlaceOrderRequest request) {
        if (request.type() == OrderType.LIMIT && request.price() == null) {
            throw new OrderValidationException("LIMIT orders require a price");
        }
        if (request.type() == OrderType.STOP && request.stopPrice() == null) {
            throw new OrderValidationException("STOP orders require a stopPrice");
        }
        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderValidationException("quantity must be positive");
        }
        // NOTE: balance/risk checks are delegated to risk-service (see risk-service module) —
        // this method only enforces order-shape invariants that belong to this service.
    }

    private Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(), e.getIdempotencyKey(), e.getUserId(), e.getMarket(), e.getSide(), e.getType(),
                e.getPrice() != null ? Money.of(e.getPrice()) : null,
                e.getStopPrice() != null ? Money.of(e.getStopPrice()) : null,
                Money.of(e.getQuantity()), Money.of(e.getFilledQuantity()), e.getStatus(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
