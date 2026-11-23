package com.quidax.liquidityengine.order.entity;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderStatus;
import com.quidax.liquidityengine.common.model.OrderType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType type;

    @Column(precision = 38, scale = 8)
    private BigDecimal price;

    @Column(name = "stop_price", precision = 38, scale = 8)
    private BigDecimal stopPrice;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal quantity;

    @Column(name = "filled_quantity", nullable = false, precision = 38, scale = 8)
    private BigDecimal filledQuantity = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal reserved = BigDecimal.ZERO;

    @Version
    private Long version;

    protected OrderEntity() {
        // JPA
    }

    public OrderEntity(UUID id, String idempotencyKey, UUID userId, String market, OrderSide side,
                        OrderType type, BigDecimal price, BigDecimal stopPrice, BigDecimal quantity,
                        OrderStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.market = market;
        this.side = side;
        this.type = type;
        this.price = price;
        this.stopPrice = stopPrice;
        this.quantity = quantity;
        this.filledQuantity = BigDecimal.ZERO;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public BigDecimal getReserved() { return reserved; }
    public void setReserved(BigDecimal reserved) { this.reserved = reserved; }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getUserId() { return userId; }
    public String getMarket() { return market; }
    public OrderSide getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    public void markOpen() {
        this.status = OrderStatus.OPEN;
        this.updatedAt = Instant.now();
    }

    public void applyFill(BigDecimal fillQty) {
        this.filledQuantity = this.filledQuantity.add(fillQty);
        this.status = this.filledQuantity.compareTo(this.quantity) >= 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void reject() {
        this.status = OrderStatus.REJECTED;
        this.updatedAt = Instant.now();
    }
}
