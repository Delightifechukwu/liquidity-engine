package com.quidax.liquidityengine.marketdata.ws;

import com.quidax.liquidityengine.marketdata.service.MarketDataBroadcastService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Client connects to /ws/market-data and sends a market symbol as the first text frame
 * (e.g. "BTC-NGN") to subscribe. Kept intentionally minimal — a production version would
 * support multi-market subscription per session and authenticated connections.
 */
@Component
public class MarketDataWebSocketHandler extends TextWebSocketHandler {

    private final MarketDataBroadcastService broadcastService;

    public MarketDataWebSocketHandler(MarketDataBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String market = message.getPayload().trim();
        broadcastService.subscribe(market, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcastService.unsubscribe(session);
    }
}
