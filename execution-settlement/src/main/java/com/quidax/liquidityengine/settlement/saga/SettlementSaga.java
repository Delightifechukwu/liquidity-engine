package com.quidax.liquidityengine.settlement.saga;

import com.quidax.liquidityengine.common.model.Trade;
import com.quidax.liquidityengine.settlement.entity.*;
import com.quidax.liquidityengine.settlement.repository.AccountBalanceRepository;
import com.quidax.liquidityengine.settlement.repository.LedgerEntryRepository;
import com.quidax.liquidityengine.settlement.repository.ProcessedTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Settles a single trade as one atomic saga step: debit the seller's base-asset account,
 * credit the buyer's base-asset account, debit the buyer's quote-asset account, credit the
 * seller's quote-asset account — four ledger rows that must all commit together or none do.
 *
 * "Saga" here is a single local ACID transaction, not a distributed multi-service saga —
 * deliberately, because all four entries live in one database. The saga *pattern* (idempotent
 * steps, a durable marker of progress, safe replay) still applies and is what
 * `processed_trades` exists for: it's what lets this method be re-invoked safely if the Kafka
 * consumer crashes between committing this transaction and acking the offset.
 */
@Component
public class SettlementSaga {

    private static final Logger log = LoggerFactory.getLogger(SettlementSaga.class);
    private static final String QUOTE_ASSET = "NGN"; // simplification: all markets quote in NGN

    private final ProcessedTradeRepository processedTradeRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountBalanceRepository accountBalanceRepository;

    public SettlementSaga(ProcessedTradeRepository processedTradeRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           AccountBalanceRepository accountBalanceRepository) {
        this.processedTradeRepository = processedTradeRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountBalanceRepository = accountBalanceRepository;
    }

    @Transactional
    public void settle(Trade trade) {
        // Step 1: idempotency gate. Inserting this row IS the commitment to process the
        // trade exactly once; if it already exists as COMPLETED, we stop here rather than
        // touching the ledger a second time.
        var existing = processedTradeRepository.findById(trade.tradeId());
        if (existing.isPresent() && existing.get().getStatus() == ProcessedTradeEntity.Status.COMPLETED) {
            log.info("Trade {} already settled — skipping (safe redelivery)", trade.tradeId());
            throw new DuplicateTradeException(trade.tradeId());
        }

        ProcessedTradeEntity tracking = existing.orElseGet(() ->
                new ProcessedTradeEntity(trade.tradeId(), ProcessedTradeEntity.Status.IN_PROGRESS, 1, Instant.now()));
        if (existing.isPresent()) {
            tracking.incrementAttempt();
        }
        processedTradeRepository.save(tracking);

        String baseAsset = trade.market().split("-")[0]; // "BTC-NGN" -> "BTC"

        BigDecimal quantity = trade.quantity().value();
        BigDecimal notional = trade.notional().value();

        // Base asset leg: seller gives up `quantity` of base asset, buyer receives it.
        postEntry(trade.tradeId(), trade.sellOrderId(), EntryType.DEBIT, baseAsset, quantity);
        postEntry(trade.tradeId(), trade.buyOrderId(), EntryType.CREDIT, baseAsset, quantity);

        // Quote asset leg: buyer pays `notional`, seller receives it (fees omitted from this
        // simplified ledger — a production version posts a third entry per leg to a fee
        // revenue account).
        postEntry(trade.tradeId(), trade.buyOrderId(), EntryType.DEBIT, QUOTE_ASSET, notional);
        postEntry(trade.tradeId(), trade.sellOrderId(), EntryType.CREDIT, QUOTE_ASSET, notional);

        tracking.markCompleted();
        processedTradeRepository.save(tracking);

        log.info("Trade {} settled: {} {} @ {} (notional {})",
                trade.tradeId(), quantity, baseAsset, trade.price(), notional);
    }

    private void postEntry(UUID tradeId, UUID accountId, EntryType type, String asset, BigDecimal amount) {
        ledgerEntryRepository.save(new LedgerEntryEntity(
                UUID.randomUUID(), tradeId, accountId, type, asset, amount, Instant.now()));

        AccountBalanceEntity balance = accountBalanceRepository.findForUpdate(accountId, asset)
                .orElseGet(() -> new AccountBalanceEntity(accountId, asset, BigDecimal.ZERO, Instant.now()));

        if (type == EntryType.DEBIT) {
            balance.debit(amount);
        } else {
            balance.credit(amount);
        }
        accountBalanceRepository.save(balance);
    }
}
