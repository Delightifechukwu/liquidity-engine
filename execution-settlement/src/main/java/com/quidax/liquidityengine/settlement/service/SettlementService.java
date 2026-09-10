package com.quidax.liquidityengine.settlement.service;

import com.quidax.liquidityengine.common.event.SettlementCompletedEvent;
import com.quidax.liquidityengine.common.event.TradeExecutedEvent;
import com.quidax.liquidityengine.common.util.Topics;
import com.quidax.liquidityengine.settlement.saga.DuplicateTradeException;
import com.quidax.liquidityengine.settlement.saga.SettlementSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementSaga settlementSaga;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SettlementService(SettlementSaga settlementSaga, KafkaTemplate<String, Object> kafkaTemplate) {
        this.settlementSaga = settlementSaga;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void handleTradeExecuted(TradeExecutedEvent event) {
        var trade = event.trade();
        try {
            settlementSaga.settle(trade);
        } catch (DuplicateTradeException e) {
            // Already settled — this is the expected, healthy outcome of a redelivered
            // event, not an error. Fall through and let the listener ack normally.
            log.info(e.getMessage());
            return;
        }

        SettlementCompletedEvent completed = new SettlementCompletedEvent(
                UUID.randomUUID(), trade.tradeId(), trade.buyOrderId(), trade.sellOrderId(),
                trade.quantity().value(), trade.price().value(), Instant.now());

        kafkaTemplate.send(Topics.SETTLEMENTS_COMPLETED, trade.tradeId().toString(), completed);
    }
}
