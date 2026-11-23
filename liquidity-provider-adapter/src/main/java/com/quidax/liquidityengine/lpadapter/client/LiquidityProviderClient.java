package com.quidax.liquidityengine.lpadapter.client;

import com.quidax.liquidityengine.lpadapter.mock.MockLiquidityProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * The resilience story, in order of what fires first: TimeLimiter bounds how long we wait
 * for a single call; Retry re-attempts a small number of times on failure; CircuitBreaker
 * stops calling an LP that's clearly down (>50% failures in the last 20 calls) for a cooldown
 * window instead of letting every incoming order queue up behind a doomed external call. The
 * fallback method returns a clearly-flagged degraded quote rather than throwing, so the
 * caller (order routing) can make an explicit decision — reject the order, route to a
 * different LP, or hold — instead of an unhandled exception surfacing as a 500.
 */
@Component
public class LiquidityProviderClient {

    private static final Logger log = LoggerFactory.getLogger(LiquidityProviderClient.class);

    private final MockLiquidityProvider mockLiquidityProvider;

    public LiquidityProviderClient(MockLiquidityProvider mockLiquidityProvider) {
        this.mockLiquidityProvider = mockLiquidityProvider;
    }

    public record Quote(UUID quoteId, String market, BigDecimal price, BigDecimal availableQuantity, boolean degraded, Instant expiresAt) {}

    @CircuitBreaker(name = "externalLiquidityProvider", fallbackMethod = "fallbackQuote")
    @Retry(name = "externalLiquidityProvider")
    @TimeLimiter(name = "externalLiquidityProvider")
    public CompletableFuture<Quote> getQuote(String market) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = mockLiquidityProvider.fetchQuote(market);
                return new Quote(response.quoteId(), response.market(), response.price(),
                        response.availableQuantity(), false, Instant.now().plusSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Fallback signature must match the original method plus the Throwable that triggered it.
     * Returning a degraded-but-valid response (rather than propagating the exception) keeps
     * order routing simple: check `degraded`, decide whether a stale/wide quote is acceptable
     * for this order size, rather than every caller needing its own try/catch around the LP call.
     */
    public CompletableFuture<Quote> fallbackQuote(String market, Throwable t) {
        log.warn("Liquidity provider unavailable for market {} — returning degraded quote. Cause: {}",
                market, t.toString());
        return CompletableFuture.completedFuture(
                new Quote(UUID.randomUUID(), market, BigDecimal.ZERO, BigDecimal.ZERO, true, Instant.EPOCH));
    }
}
