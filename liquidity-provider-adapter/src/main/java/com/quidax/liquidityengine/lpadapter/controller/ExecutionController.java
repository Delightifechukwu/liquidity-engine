package com.quidax.liquidityengine.lpadapter.controller;

import com.quidax.liquidityengine.common.model.ProviderExecution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {
    private static final BigDecimal OFFER = new BigDecimal("49000000");
    private final JdbcTemplate jdbc;
    private final long delayMs;
    private final byte[] token;

    public ExecutionController(JdbcTemplate jdbc, @Value("${provider-execution.acceptance-delay-ms}") long delayMs,
                               @Value("${provider-execution.token}") String token) {
        this.jdbc = jdbc;
        this.delayMs = delayMs;
        if (token.isBlank()) throw new IllegalArgumentException("Provider token is required");
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}")
    public CompletableFuture<ProviderExecution> submit(@PathVariable UUID id,
                                                       @RequestBody ProviderExecution.Request request,
                                                       @RequestHeader(value = "X-Provider-Token", defaultValue = "") String token) {
        authorize(token);
        validate(request);
        BigDecimal quantity = request.quantity().divide(BigDecimal.valueOf(2), 8, RoundingMode.DOWN);
        boolean executable = request.maxPrice().compareTo(OFFER) >= 0 && quantity.signum() > 0;
        int inserted = jdbc.update("""
                INSERT INTO simulated_provider.provider_executions
                    (client_order_id, max_price, requested_quantity, executed_at, fill_id, fill_price, fill_quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (client_order_id) DO NOTHING
                """, id, request.maxPrice(), request.quantity(), Timestamp.from(Instant.now()),
                executable ? UUID.randomUUID() : null, executable ? OFFER : null, executable ? quantity : null);
        if (inserted == 0 && jdbc.update("""
                UPDATE simulated_provider.provider_executions SET submissions = submissions + 1
                WHERE client_order_id = ? AND max_price = ? AND requested_quantity = ?
                """, id, request.maxPrice(), request.quantity()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client order ID already has a different payload");
        }
        ProviderExecution report = read(id);
        if (inserted == 0 || delayMs == 0) {
            return CompletableFuture.completedFuture(report);
        }
        // The acknowledgement is delayed only after the provider record has committed.
        return CompletableFuture.supplyAsync(() -> report, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    @GetMapping("/{id}")
    public ProviderExecution lookup(@PathVariable UUID id,
                                    @RequestHeader(value = "X-Provider-Token", defaultValue = "") String token) {
        authorize(token);
        return read(id);
    }

    private void authorize(String supplied) {
        if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private ProviderExecution read(UUID id) {
        return jdbc.query("""
                SELECT * FROM simulated_provider.provider_executions WHERE client_order_id = ?
                """, (row, index) -> {
            UUID fillId = row.getObject("fill_id", UUID.class);
            var fill = fillId == null ? null : new ProviderExecution.Fill(
                    fillId, row.getBigDecimal("fill_price"), row.getBigDecimal("fill_quantity"));
            return new ProviderExecution(id, row.getTimestamp("executed_at").toInstant(), fill);
        }, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void validate(ProviderExecution.Request request) {
        BigDecimal price = request.maxPrice();
        BigDecimal quantity = request.quantity();
        if (price == null || quantity == null || price.signum() <= 0 || quantity.signum() <= 0
                || price.stripTrailingZeros().scale() > 0 || quantity.stripTrailingZeros().scale() > 8
                || price.compareTo(new BigDecimal("1000000000000")) >= 0
                || quantity.compareTo(new BigDecimal("100000000")) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid protected buy");
        }
    }
}
