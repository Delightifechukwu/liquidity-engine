package com.quidax.liquidityengine.risk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Pre-trade risk checks — this runs BEFORE an order reaches the matching engine (called
 * synchronously from order-service during placeOrder). Two categories: hard limits (notional
 * cap) that reject a single order outright, and market-level circuit breakers (kill-switch)
 * that reject ALL orders for a market showing abnormal price behaviour, regardless of the
 * individual order's own merits — the JD's "risk controls and abnormal market behaviour".
 */
@Service
public class RiskService {

    @Value("${risk.max-order-notional-ngn:100000000}")
    private BigDecimal maxOrderNotional;

    @Value("${risk.max-price-deviation-from-mid-pct:10}")
    private BigDecimal maxDeviationPct;

    private final MarketVolatilityTracker volatilityTracker;

    public RiskService(MarketVolatilityTracker volatilityTracker) {
        this.volatilityTracker = volatilityTracker;
    }

    public RiskCheckResult checkOrder(String market, BigDecimal price, BigDecimal quantity) {
        if (volatilityTracker.isHalted(market)) {
            return RiskCheckResult.reject("Market %s is halted (volatility kill-switch active)".formatted(market));
        }

        if (price != null) {
            BigDecimal notional = price.multiply(quantity);
            if (notional.compareTo(maxOrderNotional) > 0) {
                return RiskCheckResult.reject(
                        "Order notional %s exceeds max allowed %s".formatted(notional, maxOrderNotional));
            }

            BigDecimal lastPrice = volatilityTracker.lastPrice(market);
            if (lastPrice != null && lastPrice.signum() > 0) {
                BigDecimal deviationPct = price.subtract(lastPrice).abs()
                        .divide(lastPrice, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                if (deviationPct.compareTo(maxDeviationPct) > 0) {
                    return RiskCheckResult.reject(
                            "Order price deviates %s%% from last known price — exceeds %s%% limit"
                                    .formatted(deviationPct, maxDeviationPct));
                }
            }
        }

        return RiskCheckResult.approve();
    }
}
