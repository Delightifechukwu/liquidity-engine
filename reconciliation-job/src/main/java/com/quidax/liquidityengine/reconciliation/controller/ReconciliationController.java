package com.quidax.liquidityengine.reconciliation.controller;

import com.quidax.liquidityengine.reconciliation.service.ReconciliationReport;
import com.quidax.liquidityengine.reconciliation.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manual trigger — useful in a demo (run a trade, then call this immediately instead of
 * waiting for the 1am cron) and operationally, for an on-call engineer re-running
 * reconciliation for a specific window while investigating an incident.
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/run")
    public ReconciliationReport runNow(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(24, ChronoUnit.HOURS);
        return reconciliationService.reconcile(start, end);
    }
}
