package com.quidax.liquidityengine.risk.service;

public record RiskCheckResult(boolean approved, String reason) {
    public static RiskCheckResult approve() { return new RiskCheckResult(true, null); }
    public static RiskCheckResult reject(String reason) { return new RiskCheckResult(false, reason); }
}
