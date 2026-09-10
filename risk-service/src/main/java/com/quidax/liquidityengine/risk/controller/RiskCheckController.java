package com.quidax.liquidityengine.risk.controller;

import com.quidax.liquidityengine.risk.service.RiskCheckResult;
import com.quidax.liquidityengine.risk.service.RiskService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskCheckController {

    private final RiskService riskService;

    public RiskCheckController(RiskService riskService) {
        this.riskService = riskService;
    }

    public record CheckOrderRequest(String market, BigDecimal price, BigDecimal quantity) {}

    @PostMapping("/check-order")
    public RiskCheckResult checkOrder(@RequestBody CheckOrderRequest request) {
        return riskService.checkOrder(request.market(), request.price(), request.quantity());
    }
}
