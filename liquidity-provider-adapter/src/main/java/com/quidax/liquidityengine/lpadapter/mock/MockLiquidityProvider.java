package com.quidax.liquidityengine.lpadapter.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for a real external exchange/liquidity-provider API. Injects random latency and
 * a configurable failure rate so the adapter's circuit breaker, retry, and timeout policies
 * (see LiquidityProviderClient) have something realistic to react to — this is what makes
 * the "resilient integrations with exchanges/liquidity providers" requirement demonstrable
 * without needing a real exchange account.
 */
@Component
public class MockLiquidityProvider {

    @Value("${mock-lp.failure-rate:0.3}")
    private double failureRate;

    @Value("${mock-lp.min-latency-ms:50}")
    private long minLatencyMs;

    @Value("${mock-lp.max-latency-ms:400}")
    private long maxLatencyMs;

    public record QuoteResponse(UUID quoteId, String market, BigDecimal price, BigDecimal availableQuantity) {}

    public QuoteResponse fetchQuote(String market) throws InterruptedException {
        simulateLatency();
        simulateRandomFailure();

        BigDecimal basePrice = new BigDecimal("50000000");
        BigDecimal jitter = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(-0.5, 0.5) * 1_000_00);
        return new QuoteResponse(UUID.randomUUID(), market, basePrice.add(jitter), new BigDecimal("5.0"));
    }

    private void simulateLatency() throws InterruptedException {
        long latency = ThreadLocalRandom.current().nextLong(minLatencyMs, maxLatencyMs + 1);
        Thread.sleep(latency);
    }

    private void simulateRandomFailure() {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new LiquidityProviderUnavailableException("Simulated upstream failure");
        }
    }
}
