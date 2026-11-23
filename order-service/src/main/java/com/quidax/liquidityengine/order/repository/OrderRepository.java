package com.quidax.liquidityengine.order.repository;

import com.quidax.liquidityengine.order.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    // Backs the idempotency check: a retried "place order" request looks itself up here
    // before anything is inserted, so the unique DB constraint is a safety net, not the
    // primary mechanism — the primary mechanism is "check first, insert only if absent".
    Optional<OrderEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query(value = """
            SELECT * FROM orders WHERE market = 'BTC-NGN' AND side = 'SELL' AND type = 'LIMIT'
              AND status IN ('OPEN', 'PARTIALLY_FILLED') AND price <= :price
            ORDER BY price, priority
            """, nativeQuery = true)
    List<OrderEntity> findAsks(BigDecimal price);

    @Query(value = """
            SELECT * FROM orders WHERE market = 'BTC-NGN' AND side = 'BUY' AND type = 'LIMIT'
              AND status IN ('OPEN', 'PARTIALLY_FILLED') AND price >= :price
            ORDER BY price DESC, priority
            """, nativeQuery = true)
    List<OrderEntity> findBids(BigDecimal price);
}
