package com.quidax.liquidityengine.order.repository;

import com.quidax.liquidityengine.order.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    // Backs the idempotency check: a retried "place order" request looks itself up here
    // before anything is inserted, so the unique DB constraint is a safety net, not the
    // primary mechanism — the primary mechanism is "check first, insert only if absent".
    Optional<OrderEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
