package com.quidax.liquidityengine.reconciliation.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProcessedTradeViewRepository extends JpaRepository<ProcessedTradeView, UUID> {
    List<ProcessedTradeView> findByProcessedAtBetween(Instant from, Instant to);
}
