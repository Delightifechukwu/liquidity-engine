package com.quidax.liquidityengine.risk.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks a rolling reference price per market so the kill-switch (see RiskService) can
 * detect abnormal moves without depending on market-data-service being up — risk checks
 * must fail closed and independently, not inherit an outage from another service.
 */
@Component
public class MarketVolatilityTracker {

    private record PricePoint(BigDecimal price, Instant at) {}

    private final Map<String, PricePoint> lastKnownPrice = new ConcurrentHashMap<>();
    private final Map<String, Boolean> halted = new ConcurrentHashMap<>();

    public void recordPrice(String market, BigDecimal price) {
        lastKnownPrice.put(market, new PricePoint(price, Instant.now()));
    }

    public BigDecimal lastPrice(String market) {
        PricePoint p = lastKnownPrice.get(market);
        return p == null ? null : p.price();
    }

    public boolean isHalted(String market) {
        return halted.getOrDefault(market, false);
    }

    public void halt(String market) { halted.put(market, true); }
    public void resume(String market) { halted.put(market, false); }
}
