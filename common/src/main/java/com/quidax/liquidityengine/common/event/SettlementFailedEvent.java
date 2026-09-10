package com.quidax.liquidityengine.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a settlement saga step fails after exhausting retries and is routed to the
 * dead-letter topic. Consumed by an ops-alerting listener and by reconciliation-job, which
 * treats any trade with a corresponding failure event (and no completion event) as an
 * open discrepancy requiring manual review.
 */
public record SettlementFailedEvent(
        UUID eventId,
        UUID tradeId,
        String reason,
        int attemptCount,
        Instant occurredAt
) {
}
