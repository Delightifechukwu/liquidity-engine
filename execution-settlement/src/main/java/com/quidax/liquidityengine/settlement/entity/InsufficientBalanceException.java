package com.quidax.liquidityengine.settlement.entity;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(UUID accountId, String asset, BigDecimal available, BigDecimal requested) {
        super("Account %s has insufficient %s balance: available=%s requested=%s"
                .formatted(accountId, asset, available, requested));
    }
}
