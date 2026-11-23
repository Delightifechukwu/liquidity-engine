package com.quidax.liquidityengine.order.controller;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
public class RoutePreviewController {
    private final JdbcTemplate jdbc;
    private final RestClient provider;

    public RoutePreviewController(JdbcTemplate jdbc, @Value("${trading.provider-url}") String providerUrl) {
        this.jdbc = jdbc;
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(750);
        transport.setReadTimeout(1500);
        this.provider = RestClient.builder().requestFactory(transport).baseUrl(providerUrl).build();
    }

    public enum Venue { INTERNAL, MOCK_PROVIDER, NONE }

    public record Preview(Venue venue, BigDecimal price) {}

    private record Quote(String market, BigDecimal price, BigDecimal availableQuantity,
                         boolean degraded, Instant expiresAt) {}

    @GetMapping("/api/v1/routes/buy-preview")
    public Preview previewBuy(
            @RequestParam @Digits(integer = 12, fraction = 0) @DecimalMin("1") BigDecimal maxPrice,
            @RequestParam @Digits(integer = 8, fraction = 8) @DecimalMin("0.00000001") BigDecimal quantity) {
        Quote quote = fetchQuote();
        BigDecimal internal = jdbc.queryForObject("""
                SELECT min(price) FROM (
                    SELECT price FROM orders
                    WHERE market = 'BTC-NGN' AND side = 'SELL' AND type = 'LIMIT'
                      AND status IN ('OPEN', 'PARTIALLY_FILLED') AND price <= ?
                    GROUP BY price HAVING sum(quantity - filled_quantity) >= ?
                ) levels
                """, BigDecimal.class, maxPrice, quantity);
        if (quote != null && !quote.degraded() && "BTC-NGN".equals(quote.market())
                && quote.expiresAt() != null && quote.expiresAt().isAfter(Instant.now())
                && quote.price() != null && quote.price().signum() > 0 && quote.price().compareTo(maxPrice) <= 0
                && quote.availableQuantity() != null && quote.availableQuantity().compareTo(quantity) >= 0
                && (internal == null || quote.price().compareTo(internal) < 0)) {
            return new Preview(Venue.MOCK_PROVIDER, quote.price());
        }
        return internal == null ? new Preview(Venue.NONE, null) : new Preview(Venue.INTERNAL, internal);
    }

    private Quote fetchQuote() {
        try {
            return provider.get().uri("/api/v1/quotes/BTC-NGN").retrieve().body(Quote.class);
        } catch (RestClientException ex) {
            return null;
        }
    }
}
