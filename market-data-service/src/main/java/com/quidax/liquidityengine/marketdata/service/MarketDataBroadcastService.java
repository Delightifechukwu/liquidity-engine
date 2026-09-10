package com.quidax.liquidityengine.marketdata.service;

import com.quidax.liquidityengine.common.event.TradeExecutedEvent;
import com.quidax.liquidityengine.common.util.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes trade ticks off Kafka and fans them out to every WebSocket session subscribed to
 * that market. Deliberately NOT deduplicating here — market data is a best-effort broadcast
 * stream, not a source of financial truth (that's the ledger, in execution-settlement), so an
 * occasional duplicate tick displayed on a price chart is a cosmetic issue, not a correctness
 * bug worth the overhead of a dedup check on the hottest-throughput consumer in the system.
 */
@Service
public class MarketDataBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBroadcastService.class);

    private final Map<String, java.util.Set<WebSocketSession>> subscribersByMarket = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void subscribe(String market, WebSocketSession session) {
        subscribersByMarket.computeIfAbsent(market, m -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribe(WebSocketSession session) {
        subscribersByMarket.values().forEach(sessions -> sessions.remove(session));
    }

    @KafkaListener(topics = Topics.TRADES_EXECUTED, groupId = "market-data-service")
    public void onTradeExecuted(TradeExecutedEvent event) {
        var trade = event.trade();
        Tick tick = new Tick(trade.market(), trade.price().value(), trade.quantity().value(), trade.executedAt());
        broadcast(trade.market(), tick);
    }

    private void broadcast(String market, Tick tick) {
        var sessions = subscribersByMarket.get(market);
        if (sessions == null || sessions.isEmpty()) return;

        try {
            String payload = objectMapper.writeValueAsString(tick);
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast tick for market {}", market, e);
        }
    }
}
