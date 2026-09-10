package com.quidax.liquidityengine.matching.config;

import com.quidax.liquidityengine.common.event.OrderCreatedEvent;
import com.quidax.liquidityengine.common.util.Topics;
import com.quidax.liquidityengine.matching.service.MatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Manual ack mode (see application.yml: listener.ack-mode: MANUAL_IMMEDIATE) — the offset is
 * only committed after handleOrderCreated returns successfully. If the process crashes mid-
 * match, Kafka redelivers the event on restart; MatchingService's idempotency-key dedup
 * (see MatchingService) makes that redelivery safe rather than a double-fill.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final MatchingService matchingService;

    public OrderEventListener(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @KafkaListener(topics = Topics.ORDERS_CREATED, groupId = "matching-engine")
    public void onOrderCreated(OrderCreatedEvent event, Acknowledgment ack) {
        try {
            matchingService.handleOrderCreated(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process OrderCreatedEvent orderId={} — not acking, will redeliver",
                    event.order().orderId(), e);
            // Deliberately not calling ack.acknowledge(): Kafka will redeliver this record.
            // A persistent failure here (e.g. malformed event) should be routed to a DLT in
            // production via a DefaultErrorHandler + DeadLetterPublishingRecoverer, configured
            // the same way execution-settlement configures its DLT (see that module).
        }
    }
}
