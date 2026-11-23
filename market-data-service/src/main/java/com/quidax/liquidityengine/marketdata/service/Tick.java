package com.quidax.liquidityengine.marketdata.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Tick(UUID tradeId, String market, BigDecimal price, BigDecimal quantity, Instant executedAt) {}
