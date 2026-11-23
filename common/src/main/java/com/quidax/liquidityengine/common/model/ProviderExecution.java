package com.quidax.liquidityengine.common.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Terminal execution report for a simulated immediate-or-cancel buy. */
public record ProviderExecution(UUID clientOrderId, Instant executedAt, Fill fill) {
    public record Request(BigDecimal maxPrice, BigDecimal quantity) {}
    public record Fill(UUID id, BigDecimal price, BigDecimal quantity) {}
}
