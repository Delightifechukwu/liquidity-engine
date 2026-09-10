package com.quidax.liquidityengine.settlement.repository;

import com.quidax.liquidityengine.settlement.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {
    List<LedgerEntryEntity> findByTradeId(UUID tradeId);
}
