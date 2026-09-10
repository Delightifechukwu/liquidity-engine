package com.quidax.liquidityengine.settlement.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_trades")
public class ProcessedTradeEntity {

    public enum Status { IN_PROGRESS, COMPLETED, FAILED }

    @Id
    @Column(name = "trade_id")
    private UUID tradeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedTradeEntity() {}

    public ProcessedTradeEntity(UUID tradeId, Status status, int attemptCount, Instant processedAt) {
        this.tradeId = tradeId;
        this.status = status;
        this.attemptCount = attemptCount;
        this.processedAt = processedAt;
    }

    public UUID getTradeId() { return tradeId; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }

    public void markCompleted() { this.status = Status.COMPLETED; this.processedAt = Instant.now(); }
    public void markFailed() { this.status = Status.FAILED; this.processedAt = Instant.now(); }
    public void incrementAttempt() { this.attemptCount++; }
}
