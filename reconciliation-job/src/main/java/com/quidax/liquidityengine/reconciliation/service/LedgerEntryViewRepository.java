package com.quidax.liquidityengine.reconciliation.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryViewRepository extends JpaRepository<LedgerEntryView, UUID> {
    List<LedgerEntryView> findByCreatedAtBetween(Instant from, Instant to);
}
