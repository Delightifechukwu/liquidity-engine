package com.quidax.liquidityengine.reconciliation.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        Instant windowStart,
        Instant windowEnd,
        int tradesChecked,
        int unbalancedTrades,
        List<Discrepancy> discrepancies,
        Instant generatedAt
) {
    public record Discrepancy(UUID tradeId, String assetOrReason, BigDecimal imbalance) {}

    public boolean isClean() { return discrepancies.isEmpty(); }
}
