package com.quidax.liquidityengine.order.service;

import com.quidax.liquidityengine.common.model.ProviderExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class ExternalExecutionRelay {
    private static final Logger log = LoggerFactory.getLogger(ExternalExecutionRelay.class);
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final RestClient provider;

    public ExternalExecutionRelay(JdbcTemplate jdbc, OrderService orders,
                                  @Value("${trading.provider-url}") String providerUrl,
                                  @Value("${trading.provider-token}") String providerToken) {
        this.jdbc = jdbc;
        this.orders = orders;
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(750);
        transport.setReadTimeout(1000);
        this.provider = RestClient.builder().requestFactory(transport).baseUrl(providerUrl)
                .defaultHeader("X-Provider-Token", providerToken).build();
    }

    private record Intent(UUID id, ProviderExecution.Request request) {}

    @Scheduled(fixedDelay = 250)
    public void processNext() {
        // Commit the short lease before calling the provider. A crashed worker leaves a retryable intent.
        var pending = jdbc.query("""
                WITH candidate AS (
                    SELECT order_id FROM external_executions
                    WHERE completed_at IS NULL AND next_attempt_at <= clock_timestamp()
                    ORDER BY next_attempt_at, order_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE external_executions e
                SET next_attempt_at = clock_timestamp() + interval '4 seconds'
                FROM candidate c JOIN orders o ON o.id = c.order_id
                WHERE e.order_id = c.order_id
                RETURNING e.order_id, o.price, o.quantity
                """, (row, i) -> new Intent(row.getObject("order_id", UUID.class),
                        new ProviderExecution.Request(row.getBigDecimal("price"), row.getBigDecimal("quantity"))));
        if (pending.isEmpty()) {
            return;
        }
        var intent = pending.getFirst();
        try {
            var report = lookupOrSubmit(intent);
            if (report == null) {
                throw new IllegalStateException("Provider returned an empty execution report");
            }
            orders.applyProviderReport(intent.id(), report);
        } catch (RuntimeException ex) {
            log.warn("Provider execution {} remains pending: {}", intent.id(),
                    NestedExceptionUtils.getMostSpecificCause(ex).toString());
        }
    }

    private ProviderExecution lookupOrSubmit(Intent intent) {
        String resource = "/api/v1/executions/{id}";
        try {
            return provider.get().uri(resource, intent.id()).retrieve().body(ProviderExecution.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return provider.post().uri(resource, intent.id()).body(intent.request())
                    .retrieve().body(ProviderExecution.class);
        }
    }
}
