package com.quidax.liquidityengine.settlement.listener;

import com.quidax.liquidityengine.common.event.TradeExecutedEvent;
import com.quidax.liquidityengine.common.util.Topics;
import com.quidax.liquidityengine.settlement.service.SettlementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TradeEventListener {

    private final SettlementService settlementService;

    public TradeEventListener(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    // Uncaught exceptions here are handled by DefaultErrorHandler (see KafkaErrorHandlingConfig):
    // retried with backoff, then routed to the DLT after max-retry-attempts. No try/catch
    // needed at this layer — that's precisely what container-level error handling is for.
    @KafkaListener(topics = Topics.TRADES_EXECUTED, groupId = "execution-settlement")
    public void onTradeExecuted(TradeExecutedEvent event) {
        settlementService.handleTradeExecuted(event);
    }
}
