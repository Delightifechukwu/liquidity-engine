package com.quidax.liquidityengine.reconciliation.job;

import com.quidax.liquidityengine.reconciliation.service.ReconciliationReport;
import com.quidax.liquidityengine.reconciliation.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class NightlyReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyReconciliationJob.class);

    private final ReconciliationService reconciliationService;

    public NightlyReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${reconciliation.cron:0 0 1 * * *}")
    public void runNightlyReconciliation() {
        Instant end = Instant.now();
        Instant start = end.minus(24, ChronoUnit.HOURS);

        log.info("Starting nightly reconciliation for window {} -> {}", start, end);
        ReconciliationReport report = reconciliationService.reconcile(start, end);

        if (!report.isClean()) {
            // In production this fires a PagerDuty/Slack alert to the on-call engineer —
            // an unreconciled trade is exactly the kind of incident that needs a human before
            // the next business day, not a silent log line.
            log.error("RECONCILIATION FAILED: {} discrepancies found in the last 24h. Report: {}",
                    report.unbalancedTrades(), report);
        } else {
            log.info("Reconciliation clean: {} trades verified, zero discrepancies", report.tradesChecked());
        }
    }
}
