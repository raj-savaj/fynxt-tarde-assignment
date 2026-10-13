package com.fynxt.trading.repository;

import com.fynxt.trading.entity.PortfolioHolding;
import com.fynxt.trading.repository.projection.HoldingPositionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioHoldingRepository extends JpaRepository<PortfolioHolding, Long> {

    /**
     * Finds a single holding by trader ID and stock symbol using JOIN FETCH
     * to eagerly load both Trader and Stock in a single SQL query, completely avoiding N+1 queries.
     */
    @Query("SELECT h FROM PortfolioHolding h " +
           "JOIN FETCH h.trader " +
           "JOIN FETCH h.stock " +
           "WHERE h.trader.traderId = :traderId AND h.stock.stockSymbol = :stockSymbol")
    Optional<PortfolioHolding> findByTraderIdAndStockSymbol(
            @Param("traderId") String traderId,
            @Param("stockSymbol") String stockSymbol
    );

    /**
     * Pojection query for portfolio retrieval:
     * - Selects only the 3 required scalar values (stockSymbol, sector, quantity) directly from the database.
     *
     * @param traderId the ID of the trader
     * @return List of HoldingPositionProjection representing active positions
     */
    @Query("SELECT h.stock.stockSymbol AS stockSymbol, " +
           "h.stock.sector AS sector, " +
           "h.quantity AS quantity " +
           "FROM PortfolioHolding h " +
           "WHERE h.trader.traderId = :traderId AND h.quantity > 0")
    List<HoldingPositionProjection> findHoldingPositionsByTraderId(@Param("traderId") String traderId);
}
