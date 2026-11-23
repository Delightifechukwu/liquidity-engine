package com.quidax.liquidityengine.order.controller;

import com.quidax.liquidityengine.order.entity.OrderEntity;
import com.quidax.liquidityengine.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Idempotency-Key is mandatory: a client (mobile app on a flaky connection, or our own
     * retry logic) that resubmits the same request with the same key gets back the original
     * order — 200, not a duplicate 201 — instead of accidentally placing two orders.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request, @AuthenticationPrincipal Jwt jwt) {

        OrderService.PlaceOrderResult result = orderService.placeOrder(userId(jwt), idempotencyKey, request);
        HttpStatus status = result.wasAlreadyPresent() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        OrderEntity order = orderService.getOrder(userId(jwt), orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        OrderEntity order = orderService.cancelOrder(userId(jwt), orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }
    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A UUID subject is required");
        }
    }
}
