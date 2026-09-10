package com.quidax.liquidityengine.matching.engine;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-market limit order book with price-time priority matching.
 *
 * Concurrency model — this is the part worth explaining out loud in an interview:
 * ConcurrentSkipListMap gives us a thread-safe, sorted price index, but matching itself
 * (walk price levels, mutate remaining quantity, remove filled orders, insert the resting
 * remainder) is a multi-step operation that must be atomic as a whole, or two threads could
 * both "see" the same resting quantity and double-fill it. So each OrderBook instance owns a
 * single ReentrantLock and every matching operation holds it for the full duration of the
 * match. Correctness comes from serializing access *per market*; throughput comes from the
 * fact that different markets (BTC-NGN, ETH-NGN, ...) have independent OrderBook instances
 * with independent locks and can match fully in parallel. This trades a small amount of
 * intra-market parallelism for a matching algorithm that's simple enough to reason about and
 * test exhaustively — the right trade-off for a component where a correctness bug means an
 * incorrect fill price or an impossible double-fill.
 */
public final class OrderBook {

    private final String market;

    // Bids: highest price first -> reverse natural order.
    private final ConcurrentSkipListMap<BigDecimal, PriceLevel> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    // Asks: lowest price first -> natural order.
    private final ConcurrentSkipListMap<BigDecimal, PriceLevel> asks = new ConcurrentSkipListMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    public OrderBook(String market) {
        this.market = market;
    }

    public String market() { return market; }

    /**
     * Submits an incoming order for matching. Returns the list of fills produced (possibly
     * empty). Any unfilled remainder of a LIMIT order is added to the book as a resting order;
     * an unfilled remainder of a MARKET order is dropped (no liquidity available — the caller
     * is responsible for deciding whether to route the remainder to the liquidity-provider
     * adapter, per the JD's "exchange and liquidity-provider integrations" requirement).
     */
    public List<MatchResult> submit(UUID orderId, OrderSide side, OrderType type,
                                     BigDecimal limitPrice, BigDecimal quantity) {
        lock.lock();
        try {
            List<MatchResult> fills = new ArrayList<>();
            BigDecimal remaining = quantity;

            ConcurrentSkipListMap<BigDecimal, PriceLevel> opposite = side == OrderSide.BUY ? asks : bids;

            for (var levelEntry : opposite.entrySet()) {
                if (remaining.signum() <= 0) break;

                BigDecimal levelPrice = levelEntry.getKey();
                if (!priceCrosses(side, type, limitPrice, levelPrice)) break;

                PriceLevel level = levelEntry.getValue();
                while (remaining.signum() > 0 && !level.isEmpty()) {
                    RestingOrder resting = level.peekFirst();
                    BigDecimal fillQty = remaining.min(resting.remainingQuantity());

                    resting.reduceBy(fillQty);
                    remaining = remaining.subtract(fillQty);
                    fills.add(MatchResult.of(orderId, resting, fillQty));

                    level.removeFirstIfFullyFilled();
                }
                if (level.isEmpty()) {
                    opposite.remove(levelPrice);
                }
            }

            // Rest the remainder, if any and if the order type allows resting.
            if (remaining.signum() > 0 && type != OrderType.MARKET && limitPrice != null) {
                RestingOrder toRest = new RestingOrder(orderId, side, limitPrice, remaining,
                        Instant.now(), sequenceGenerator.incrementAndGet());
                bookSide(side).computeIfAbsent(limitPrice, PriceLevel::new).addLast(toRest);
            }

            return fills;
        } finally {
            lock.unlock();
        }
    }

    public boolean cancel(UUID orderId, OrderSide side, BigDecimal price) {
        lock.lock();
        try {
            PriceLevel level = bookSide(side).get(price);
            if (level == null) return false;
            RestingOrder target = level.findById(orderId);
            if (target == null) return false;
            level.remove(target);
            if (level.isEmpty()) {
                bookSide(side).remove(price);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Optional<BigDecimal> bestBid() {
        var entry = bids.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getKey());
    }

    public Optional<BigDecimal> bestAsk() {
        var entry = asks.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getKey());
    }

    /** Best bid/ask spread — null if either side is empty. Used by market-data-service. */
    public Optional<BigDecimal> spread() {
        var bid = bestBid();
        var ask = bestAsk();
        if (bid.isEmpty() || ask.isEmpty()) return Optional.empty();
        return Optional.of(ask.get().subtract(bid.get()));
    }

    private boolean priceCrosses(OrderSide incomingSide, OrderType type, BigDecimal limitPrice, BigDecimal levelPrice) {
        if (type == OrderType.MARKET) return true; // market orders cross at any available price
        // incoming BUY crosses ask levels at or below its limit; incoming SELL crosses bid
        // levels at or above its limit.
        return incomingSide == OrderSide.BUY
                ? limitPrice.compareTo(levelPrice) >= 0
                : limitPrice.compareTo(levelPrice) <= 0;
    }

    private ConcurrentSkipListMap<BigDecimal, PriceLevel> bookSide(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }
}
