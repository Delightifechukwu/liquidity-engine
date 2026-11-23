package com.quidax.liquidityengine.order.service;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderStatus;
import com.quidax.liquidityengine.common.model.OrderType;
import com.quidax.liquidityengine.order.controller.PlaceOrderRequest;
import com.quidax.liquidityengine.order.entity.OrderEntity;
import com.quidax.liquidityengine.order.repository.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {
    private static final String MARKET = "BTC-NGN";
    private static final String BASE = "BTC";
    private static final String QUOTE = "NGN";

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    public OrderService(OrderRepository orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    public record PlaceOrderResult(OrderEntity order, boolean wasAlreadyPresent) {}

    @Transactional
    public PlaceOrderResult placeOrder(UUID userId, String key, PlaceOrderRequest request) {
        validate(userId, key, request);
        lockMarket();
        var existing = orders.findByUserIdAndIdempotencyKey(userId, key);
        if (existing.isPresent()) {
            var order = existing.get();
            if (!request.market().equals(order.getMarket()) || request.side() != order.getSide()
                    || request.type() != order.getType() || order.getPrice() == null
                    || request.price().compareTo(order.getPrice()) != 0
                    || request.quantity().compareTo(order.getQuantity()) != 0) {
                throw new OrderValidationException("Idempotency key already used for a different order");
            }
            return new PlaceOrderResult(order, true);
        }

        checkRisk(request);
        Instant now = Instant.now();
        var incoming = orders.saveAndFlush(new OrderEntity(UUID.randomUUID(), key, userId, MARKET,
                request.side(), request.type(), request.price(), null, request.quantity(), OrderStatus.OPEN, now, now));
        BigDecimal reservation = reservationFor(incoming, incoming.getQuantity());
        if (jdbc.update("""
                UPDATE wallets SET reserved = reserved + ?
                WHERE user_id = ? AND asset = ? AND balance - reserved >= ?
                """, reservation, userId, debitAsset(incoming), reservation) != 1) {
            throw new OrderValidationException("Insufficient available " + debitAsset(incoming));
        }
        incoming.setReserved(reservation);

        var restingOrders = incoming.getSide() == OrderSide.BUY
                ? orders.findAsks(incoming.getPrice())
                : orders.findBids(incoming.getPrice());
        for (var resting : restingOrders) {
            BigDecimal remaining = remaining(incoming);
            if (remaining.signum() == 0) {
                break;
            }
            if (resting.getUserId().equals(userId)) {
                // Cancel the incoming remainder to preserve priority without self-trading.
                cancelRemainder(incoming);
                return new PlaceOrderResult(incoming, false);
            }
            execute(incoming, resting, remaining.min(remaining(resting)));
        }
        if (incoming.getType() == OrderType.MARKET && remaining(incoming).signum() > 0) {
            cancelRemainder(incoming);
        }
        return new PlaceOrderResult(incoming, false);
    }

    @Transactional(readOnly = true)
    public OrderEntity getOrder(UUID userId, UUID orderId) {
        return orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public OrderEntity cancelOrder(UUID userId, UUID orderId) {
        lockMarket();
        var order = getOrder(userId, orderId);
        if (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            cancelRemainder(order);
        }
        return order;
    }

    private void lockMarket() {
        jdbc.queryForObject("SELECT market FROM trading_market WHERE market = ? FOR UPDATE", String.class, MARKET);
    }

    private void checkRisk(PlaceOrderRequest request) {
        if (request.price().multiply(request.quantity()).compareTo(new BigDecimal("100000000")) > 0) {
            throw new OrderValidationException("Order notional exceeds 100,000,000 NGN");
        }
        boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT halted OR COALESCE(abs(? - last_price) > last_price * 0.10, false)
                FROM trading_market WHERE market = ?
                """, Boolean.class, request.price(), MARKET));
        if (blocked) {
            throw new OrderValidationException("Market halted or order price exceeds the 10% price band");
        }
    }

    private void execute(OrderEntity incoming, OrderEntity resting, BigDecimal quantity) {
        var buy = incoming.getSide() == OrderSide.BUY ? incoming : resting;
        var sell = buy == incoming ? resting : incoming;
        var price = resting.getPrice();
        var notional = price.multiply(quantity);
        UUID tradeId = UUID.randomUUID();
        jdbc.update("INSERT INTO trading_fills VALUES (?, ?, ?, ?, ?, ?)",
                tradeId, buy.getId(), sell.getId(), price, quantity, Timestamp.from(Instant.now()));
        debit(buy, quantity, notional);
        debit(sell, quantity, quantity);
        credit(buy.getUserId(), BASE, quantity);
        credit(sell.getUserId(), QUOTE, notional);
        post(tradeId, BASE, -1, sell.getUserId(), quantity);
        post(tradeId, BASE, 1, buy.getUserId(), quantity);
        post(tradeId, QUOTE, -1, buy.getUserId(), notional);
        post(tradeId, QUOTE, 1, sell.getUserId(), notional);
        jdbc.update("UPDATE trading_market SET last_price = ? WHERE market = ?", price, MARKET);
        jdbc.update("INSERT INTO trade_outbox(trade_id) VALUES (?)", tradeId);
    }

    private void debit(OrderEntity order, BigDecimal quantity, BigDecimal amount) {
        BigDecimal released = reservationFor(order, quantity);
        updateDebitWallet(order, amount, released);
        order.setReserved(order.getReserved().subtract(released));
        order.applyFill(quantity);
    }

    private void credit(UUID userId, String asset, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO wallets (user_id, asset, balance) VALUES (?, ?, ?)
                ON CONFLICT (user_id, asset) DO UPDATE SET balance = wallets.balance + EXCLUDED.balance
                """, userId, asset, amount);
    }

    private void post(UUID tradeId, String asset, int direction, UUID userId, BigDecimal amount) {
        jdbc.update("INSERT INTO trading_postings VALUES (?, ?, ?, ?, ?)", tradeId, asset, direction, userId, amount);
    }

    private void cancelRemainder(OrderEntity order) {
        updateDebitWallet(order, BigDecimal.ZERO, order.getReserved());
        order.setReserved(BigDecimal.ZERO);
        order.cancel();
    }

    private void updateDebitWallet(OrderEntity order, BigDecimal debit, BigDecimal release) {
        if (jdbc.update("""
                UPDATE wallets SET balance = balance - ?, reserved = reserved - ?
                WHERE user_id = ? AND asset = ?
                """, debit, release, order.getUserId(), debitAsset(order)) != 1) {
            throw new IllegalStateException("Reserved wallet missing for order " + order.getId());
        }
    }

    private static BigDecimal remaining(OrderEntity order) {
        return order.getQuantity().subtract(order.getFilledQuantity());
    }

    private static BigDecimal reservationFor(OrderEntity order, BigDecimal quantity) {
        return order.getSide() == OrderSide.BUY ? order.getPrice().multiply(quantity) : quantity;
    }

    private static String debitAsset(OrderEntity order) {
        return order.getSide() == OrderSide.BUY ? QUOTE : BASE;
    }

    private static void validate(UUID userId, String key, PlaceOrderRequest request) {
        if (request.userId() != null && !userId.equals(request.userId())) {
            throw new OrderValidationException("Order user must match the authenticated subject");
        }
        if (key.isBlank() || key.length() > 128) {
            throw new OrderValidationException("Idempotency-Key must contain 1 to 128 characters");
        }
        if (!MARKET.equals(request.market()) || request.type() == OrderType.STOP
                || request.stopPrice() != null || request.price() == null) {
            throw new OrderValidationException("BTC-NGN accepts LIMIT and price-protected MARKET orders");
        }
    }
}
