package com.quidax.liquidityengine.settlement.saga;

import java.util.UUID;

/** Thrown when a trade has already been fully settled — signals the caller (Kafka listener)
 *  to ack and move on rather than retry. */
public class DuplicateTradeException extends RuntimeException {
    public DuplicateTradeException(UUID tradeId) {
        super("Trade already settled: " + tradeId);
    }
}
