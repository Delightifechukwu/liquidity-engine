package com.quidax.liquidityengine.common.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single match between a buy and a sell order. This is the unit that flows downstream
 * into settlement, market data, and reconciliation — it must never be re-emitted for the
 * same match (idempotency is enforced via tradeId + the originating orders' idempotency keys).
 */
public record Trade(
        UUID tradeId,
        String market,
        UUID buyOrderId,
        UUID sellOrderId,
        Money price,
        Money quantity,
        Money buyerFee,
        Money sellerFee,
        Instant executedAt
) {
    public Money notional() {
        return price.multiply(quantity.value());
    }
}
