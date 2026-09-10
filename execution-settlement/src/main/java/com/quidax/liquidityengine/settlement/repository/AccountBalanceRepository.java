package com.quidax.liquidityengine.settlement.repository;

import com.quidax.liquidityengine.settlement.entity.AccountBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceRepository
        extends JpaRepository<AccountBalanceEntity, AccountBalanceEntity.Key> {

    /**
     * Pessimistic lock on the balance row for the duration of the settlement transaction.
     * Two trades settling against the same account concurrently must not read-modify-write
     * the same balance in an interleaved way — that's a classic lost-update race. The
     * alternative (optimistic locking via @Version + retry) also works and is used on
     * OrderEntity in order-service; pessimistic locking is the better trade-off here because
     * settlement contention on a hot account is common and retry storms under optimistic
     * locking would themselves become a throughput problem.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalanceEntity b where b.accountId = :accountId and b.asset = :asset")
    Optional<AccountBalanceEntity> findForUpdate(UUID accountId, String asset);
}
