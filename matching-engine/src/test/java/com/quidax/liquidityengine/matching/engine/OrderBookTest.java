package com.quidax.liquidityengine.matching.engine;

import com.quidax.liquidityengine.common.model.OrderSide;
import com.quidax.liquidityengine.common.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    @Test
    void limitOrderRestsWhenNoOppositeLiquidity() {
        OrderBook book = new OrderBook("BTC-NGN");

        List<MatchResult> fills = book.submit(UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("0.5"));

        assertThat(fills).isEmpty();
        assertThat(book.bestBid()).contains(new BigDecimal("50000000"));
    }

    @Test
    void crossingLimitOrderMatchesAtRestingPrice() {
        OrderBook book = new OrderBook("BTC-NGN");
        UUID sellerId = UUID.randomUUID();

        // Resting sell at 50,000,000
        book.submit(sellerId, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("1.0"));

        // Incoming buy crosses at 50,500,000 -> should fill at the RESTING price (50,000,000),
        // not the incoming order's limit — this is the standard price-time priority rule and
        // a common place for matching-engine bugs to hide.
        UUID buyerId = UUID.randomUUID();
        List<MatchResult> fills = book.submit(buyerId, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("50500000"), new BigDecimal("1.0"));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).price()).isEqualByComparingTo("50000000");
        assertThat(fills.get(0).quantity()).isEqualByComparingTo("1.0");
        assertThat(book.bestAsk()).isEmpty();
        assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void partialFillLeavesRemainderResting() {
        OrderBook book = new OrderBook("BTC-NGN");
        book.submit(UUID.randomUUID(), OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("0.3"));

        List<MatchResult> fills = book.submit(UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("1.0"));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).quantity()).isEqualByComparingTo("0.3");
        // 0.7 of the buy order should now be resting as the new best bid
        assertThat(book.bestBid()).contains(new BigDecimal("50000000"));
    }

    @Test
    void timePriorityFillsEarlierRestingOrderFirst() {
        OrderBook book = new OrderBook("BTC-NGN");
        UUID firstSeller = UUID.randomUUID();
        UUID secondSeller = UUID.randomUUID();

        book.submit(firstSeller, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("50000000"), new BigDecimal("0.5"));
        book.submit(secondSeller, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("50000000"), new BigDecimal("0.5"));

        List<MatchResult> fills = book.submit(UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("0.5"));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).restingOrderId()).isEqualTo(firstSeller);
    }

    @Test
    void marketOrderDoesNotRestUnfilledRemainder() {
        OrderBook book = new OrderBook("BTC-NGN");
        // No resting liquidity at all.
        List<MatchResult> fills = book.submit(UUID.randomUUID(), OrderSide.BUY, OrderType.MARKET,
                null, new BigDecimal("1.0"));

        assertThat(fills).isEmpty();
        assertThat(book.bestBid()).isEmpty(); // must NOT rest — market orders never sit on the book
    }

    @Test
    void concurrentSubmissionsFromMultipleThreadsProduceNoDoubleFillOrLostQuantity() throws InterruptedException, ExecutionException {
        // Simulates the exact risk called out in the JD: "distributed systems that behave
        // correctly under concurrency, retries, duplicate events". 50 buy threads race against
        // a single large resting sell order; total filled quantity across all threads must
        // exactly equal the smaller of (total demand, available supply) — no more, no less.
        OrderBook book = new OrderBook("BTC-NGN");
        book.submit(UUID.randomUUID(), OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("50000000"), new BigDecimal("50.0"));

        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<List<MatchResult>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return book.submit(UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT,
                        new BigDecimal("50000000"), new BigDecimal("1.0"));
            }));
        }

        startGate.countDown();
        BigDecimal totalFilled = BigDecimal.ZERO;
        for (Future<List<MatchResult>> f : futures) {
            for (MatchResult m : f.get()) {
                totalFilled = totalFilled.add(m.quantity());
            }
        }
        pool.shutdown();

        assertThat(totalFilled).isEqualByComparingTo("50.0"); // exactly the available supply, no more
        assertThat(book.bestAsk()).isEmpty(); // fully consumed, not over- or under-drained
    }
}
