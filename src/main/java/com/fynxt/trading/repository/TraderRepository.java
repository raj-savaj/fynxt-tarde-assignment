package com.fynxt.trading.repository;

import com.fynxt.trading.entity.Trader;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TraderRepository extends JpaRepository<Trader, String> {

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the trader record.
     * Prevents race conditions during concurrent order placement for the same trader.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trader t WHERE t.traderId = :traderId")
    Optional<Trader> findByIdForUpdate(@Param("traderId") String traderId);
}
