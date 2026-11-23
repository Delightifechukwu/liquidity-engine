package com.quidax.liquidityengine.order.controller;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Idempotency-Key is taken from the request header (standard pattern for POST endpoints
 * that must be safely retryable), not the body — see OrderController.
 */
public record PlaceOrderRequest(
        UUID userId,
        @NotBlank String market,
        @NotNull OrderSide side,
        @NotNull OrderType type,
        @Digits(integer = 12, fraction = 0) @DecimalMin(value = "0.0", inclusive = false) BigDecimal price,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal stopPrice,
        @NotNull @Digits(integer = 8, fraction = 8) @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity
) {
}
