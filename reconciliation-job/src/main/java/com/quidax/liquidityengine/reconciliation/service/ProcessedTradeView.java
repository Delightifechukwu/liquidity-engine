package com.quidax.liquidityengine.reconciliation.service;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_trades")
public class ProcessedTradeView {

    @Id
    @Column(name = "trade_id")
    private UUID tradeId;

    private String status;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessedTradeView() {}

    public UUID getTradeId() { return tradeId; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getProcessedAt() { return processedAt; }
}
