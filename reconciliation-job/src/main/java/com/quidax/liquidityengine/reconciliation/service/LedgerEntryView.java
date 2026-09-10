package com.quidax.liquidityengine.reconciliation.service;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only mapping onto execution-settlement's `ledger_entries` table. reconciliation-job
 * deliberately reads the same schema rather than calling execution-settlement's API —
 * reconciliation's entire purpose is to catch cases where the *service* might be lying to
 * itself (a bug in its own accounting logic), so it needs to see the raw, persisted ledger,
 * not a view mediated by the same code path being audited.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryView {

    @Id
    private UUID id;

    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "entry_type")
    private String entryType;

    private String asset;

    private BigDecimal amount;

    @Column(name = "created_at")
    private Instant createdAt;

    protected LedgerEntryView() {}

    public UUID getId() { return id; }
    public UUID getTradeId() { return tradeId; }
    public UUID getAccountId() { return accountId; }
    public String getEntryType() { return entryType; }
    public String getAsset() { return asset; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}
