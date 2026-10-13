package com.fynxt.trading.repository;

import com.fynxt.trading.entity.TradeOrder;
import com.fynxt.trading.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    /**
     * Counts the number of orders in a given status (e.g. PENDING) for a trader.
     * Uses a direct COUNT query returning a scalar, avoiding loading entities into memory.
     */
    @Query("SELECT COUNT(o) FROM TradeOrder o WHERE o.trader.traderId = :traderId AND o.status = :status")
    long countByTraderIdAndStatus(@Param("traderId") String traderId, @Param("status") OrderStatus status);

    /**
     * Retrieves all orders for a trader ordered by creation date.
     * Uses JOIN FETCH to avoid N+1 select queries when reading stock information.
     */
    @Query("SELECT o FROM TradeOrder o JOIN FETCH o.stock WHERE o.trader.traderId = :traderId ORDER BY o.createdAt DESC")
    List<TradeOrder> findAllByTraderIdOrderByCreatedAtDesc(@Param("traderId") String traderId);

    /**
     * Retrieves all orders in a specific status for a trader.
     */
    @Query("SELECT o FROM TradeOrder o JOIN FETCH o.stock WHERE o.trader.traderId = :traderId AND o.status = :status")
    List<TradeOrder> findAllByTraderIdAndStatus(@Param("traderId") String traderId, @Param("status") OrderStatus status);

    /**
     * Acquires a pessimistic lock on an order row for atomic status transition (e.g., PENDING -> FILLED or CANCELLED)
     * eagerly fetching associated trader and stock to prevent N+1 queries.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM TradeOrder o JOIN FETCH o.trader JOIN FETCH o.stock WHERE o.orderId = :orderId")
    Optional<TradeOrder> findByIdForUpdate(@Param("orderId") Long orderId);
}
