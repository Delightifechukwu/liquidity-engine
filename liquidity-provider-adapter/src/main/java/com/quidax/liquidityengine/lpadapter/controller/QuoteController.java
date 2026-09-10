package com.quidax.liquidityengine.lpadapter.controller;

import com.quidax.liquidityengine.lpadapter.client.LiquidityProviderClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
public class QuoteController {

    private final LiquidityProviderClient client;

    public QuoteController(LiquidityProviderClient client) {
        this.client = client;
    }

    @GetMapping("/api/v1/quotes/{market}")
    public CompletableFuture<LiquidityProviderClient.Quote> getQuote(@PathVariable String market) {
        return client.getQuote(market);
    }
}
