package com.quidax.liquidityengine.order.controller;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderStatus;
import com.quidax.liquidityengine.common.model.OrderType;
import com.quidax.liquidityengine.order.entity.OrderEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String market,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(OrderEntity e) {
        return new OrderResponse(e.getId(), e.getMarket(), e.getSide(), e.getType(),
                e.getPrice(), e.getQuantity(), e.getFilledQuantity(), e.getStatus(), e.getCreatedAt());
    }
}
