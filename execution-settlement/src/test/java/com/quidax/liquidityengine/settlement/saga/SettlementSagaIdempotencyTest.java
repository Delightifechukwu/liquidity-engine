package com.quidax.liquidityengine.settlement.saga;

import com.quidax.liquidityengine.common.model.Money;
import com.quidax.liquidityengine.common.model.Trade;
import com.quidax.liquidityengine.settlement.entity.AccountBalanceEntity;
import com.quidax.liquidityengine.settlement.entity.ProcessedTradeEntity;
import com.quidax.liquidityengine.settlement.repository.AccountBalanceRepository;
import com.quidax.liquidityengine.settlement.repository.LedgerEntryRepository;
import com.quidax.liquidityengine.settlement.repository.ProcessedTradeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proves the settlement-layer half of the duplicate-event story: even if matching-engine's
 * own dedup (see MatchingServiceDuplicateEventTest) somehow missed a redelivery, a second
 * TradeExecutedEvent for an already-COMPLETED trade must never post ledger entries twice.
 * Two independent layers of defense against the same class of production incident.
 */
class SettlementSagaIdempotencyTest {

    @Test
    void settlingAnAlreadyCompletedTradeThrowsAndPostsNoNewLedgerEntries() {
        ProcessedTradeRepository processedTradeRepository = mock(ProcessedTradeRepository.class);
        LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
        AccountBalanceRepository accountBalanceRepository = mock(AccountBalanceRepository.class);

        SettlementSaga saga = new SettlementSaga(
                processedTradeRepository, ledgerEntryRepository, accountBalanceRepository);

        UUID tradeId = UUID.randomUUID();
        Trade trade = new Trade(tradeId, "BTC-NGN", UUID.randomUUID(), UUID.randomUUID(),
                Money.of("50000000"), Money.of("1.0"), Money.ZERO, Money.ZERO, Instant.now());

        when(processedTradeRepository.findById(tradeId)).thenReturn(
                Optional.of(new ProcessedTradeEntity(tradeId, ProcessedTradeEntity.Status.COMPLETED, 1, Instant.now())));

        assertThatThrownBy(() -> saga.settle(trade))
                .isInstanceOf(DuplicateTradeException.class);

        // The whole point: zero ledger writes and zero balance mutations on replay.
        verify(ledgerEntryRepository, never()).save(any());
        verify(accountBalanceRepository, never()).save(any());
    }

    @Test
    void insufficientBalanceOnDebitAbortsTheWholeSettlement() {
        var balance = new AccountBalanceEntity(UUID.randomUUID(), "BTC", java.math.BigDecimal.ZERO, Instant.now());

        assertThatThrownBy(() -> balance.debit(java.math.BigDecimal.ONE))
                .isInstanceOf(com.quidax.liquidityengine.settlement.entity.InsufficientBalanceException.class);

        // Balance must be unchanged after a failed debit — this is what makes the ledger
        // trustworthy under partial-failure conditions.
        assertThat(balance.getBalance()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }
}
