package com.quidax.liquidityengine.settlement.repository;

import com.quidax.liquidityengine.settlement.entity.ProcessedTradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedTradeRepository extends JpaRepository<ProcessedTradeEntity, UUID> {
}
