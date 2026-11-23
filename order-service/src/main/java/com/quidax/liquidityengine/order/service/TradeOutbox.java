package com.quidax.liquidityengine.order.service;

import com.quidax.liquidityengine.common.event.TradeExecutedEvent;
import com.quidax.liquidityengine.common.model.Money;
import com.quidax.liquidityengine.common.model.Trade;
import com.quidax.liquidityengine.common.util.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class TradeOutbox {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;

    public TradeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, Object> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${trading.outbox-delay-ms:250}")
    @Transactional
    public void publish() {
        var pending = jdbc.query("""
                SELECT f.*, o.market FROM trade_outbox p
                JOIN trading_fills f ON f.id = p.trade_id
                JOIN orders o ON o.id = f.buy_order_id
                WHERE p.published_at IS NULL ORDER BY p.sequence LIMIT 1 FOR UPDATE OF p
                """, (row, index) -> new Trade(
                row.getObject("id", UUID.class), row.getString("market"),
                row.getObject("buy_order_id", UUID.class), row.getObject("sell_order_id", UUID.class),
                Money.of(row.getBigDecimal("price")), Money.of(row.getBigDecimal("quantity")),
                Money.ZERO, Money.ZERO, row.getTimestamp("executed_at").toInstant()));
        if (pending.isEmpty()) {
            return;
        }
        var trade = pending.getFirst();
        try {
            kafka.send(Topics.TRADES_SETTLED, trade.market(),
                    new TradeExecutedEvent(trade.tradeId(), trade, trade.executedAt())).get(6, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Trade publication interrupted", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Trade publication failed for " + trade.tradeId(), ex);
        }
        jdbc.update("UPDATE trade_outbox SET published_at = now() WHERE trade_id = ?", trade.tradeId());
    }
}
