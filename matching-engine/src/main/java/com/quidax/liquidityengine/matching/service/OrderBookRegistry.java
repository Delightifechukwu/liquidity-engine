package com.quidax.liquidityengine.matching.service;

import com.quidax.liquidityengine.matching.engine.OrderBook;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One OrderBook per market, created lazily. Each market's book is fully independent —
 * this is what lets matching-engine scale horizontally by market (partition Kafka topics
 * by market key, run one consumer instance per partition/market group) without any
 * cross-instance coordination.
 */
@Component
public class OrderBookRegistry {

    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();

    public OrderBook forMarket(String market) {
        return books.computeIfAbsent(market, OrderBook::new);
    }
}
