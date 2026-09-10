package com.quidax.liquidityengine.marketdata.service;

import java.math.BigDecimal;
import java.time.Instant;

public record Tick(String market, BigDecimal price, BigDecimal quantity, Instant executedAt) {}
