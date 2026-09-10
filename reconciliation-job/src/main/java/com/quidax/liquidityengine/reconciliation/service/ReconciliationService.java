package com.quidax.liquidityengine.reconciliation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Two independent checks over the same window, mirroring the JD's "financial records remain
 * accurate and fully auditable":
 *
 *  1. Ledger balance check — for every trade_id, sum(DEBIT) must equal sum(CREDIT) per asset.
 *     A double-entry ledger that doesn't net to zero for some trade means the accounting
 *     logic itself has a bug (or a partial write slipped through outside a transaction) —
 *     this is the check that would have caught a broken settlement saga before it reached
 *     production, if this project had a production.
 *
 *  2. Stuck-trade check — any trade in `processed_trades` with status IN_PROGRESS for longer
 *     than a grace period indicates a settlement that crashed mid-saga and never resolved to
 *     COMPLETED or FAILED. Combined with the DLT (see execution-settlement's
 *     KafkaErrorHandlingConfig), this is the second half of "retries, recovery" — DLT catches
 *     failures the *consumer* noticed; this check catches failures where the consumer itself
 *     died silently.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final long STUCK_TRADE_GRACE_MINUTES = 15;

    private final LedgerEntryViewRepository ledgerEntryViewRepository;
    private final ProcessedTradeViewRepository processedTradeViewRepository;

    public ReconciliationService(LedgerEntryViewRepository ledgerEntryViewRepository,
                                  ProcessedTradeViewRepository processedTradeViewRepository) {
        this.ledgerEntryViewRepository = ledgerEntryViewRepository;
        this.processedTradeViewRepository = processedTradeViewRepository;
    }

    public ReconciliationReport reconcile(Instant windowStart, Instant windowEnd) {
        List<LedgerEntryView> entries = ledgerEntryViewRepository.findByCreatedAtBetween(windowStart, windowEnd);
        List<ReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();

        Map<UUID, List<LedgerEntryView>> byTrade = entries.stream()
                .collect(Collectors.groupingBy(LedgerEntryView::getTradeId));

        for (var tradeEntry : byTrade.entrySet()) {
            UUID tradeId = tradeEntry.getKey();
            Map<String, BigDecimal> netByAsset = new HashMap<>();

            for (LedgerEntryView e : tradeEntry.getValue()) {
                BigDecimal signed = "DEBIT".equals(e.getEntryType()) ? e.getAmount().negate() : e.getAmount();
                netByAsset.merge(e.getAsset(), signed, BigDecimal::add);
            }

            for (var assetNet : netByAsset.entrySet()) {
                if (assetNet.getValue().compareTo(BigDecimal.ZERO) != 0) {
                    discrepancies.add(new ReconciliationReport.Discrepancy(
                            tradeId, assetNet.getKey(), assetNet.getValue()));
                    log.warn("RECONCILIATION MISMATCH trade={} asset={} netImbalance={}",
                            tradeId, assetNet.getKey(), assetNet.getValue());
                }
            }
        }

        List<ProcessedTradeView> stuck = processedTradeViewRepository.findByProcessedAtBetween(windowStart, windowEnd)
                .stream()
                .filter(t -> "IN_PROGRESS".equals(t.getStatus()))
                .filter(t -> t.getProcessedAt().isBefore(Instant.now().minus(STUCK_TRADE_GRACE_MINUTES, ChronoUnit.MINUTES)))
                .toList();

        for (ProcessedTradeView t : stuck) {
            discrepancies.add(new ReconciliationReport.Discrepancy(
                    t.getTradeId(), "STUCK_IN_PROGRESS", BigDecimal.ZERO));
            log.warn("RECONCILIATION: trade {} stuck IN_PROGRESS since {} — settlement likely crashed mid-saga",
                    t.getTradeId(), t.getProcessedAt());
        }

        var report = new ReconciliationReport(windowStart, windowEnd, byTrade.size(),
                discrepancies.size(), discrepancies, Instant.now());

        log.info("Reconciliation complete: {} trades checked, {} discrepancies found",
                report.tradesChecked(), report.unbalancedTrades());

        return report;
    }
}
