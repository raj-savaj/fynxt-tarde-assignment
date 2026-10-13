package com.fynxt.trading.service.impl;

import com.fynxt.trading.dto.request.AddToPortfolioRequest;
import com.fynxt.trading.dto.response.PortfolioResponse;
import com.fynxt.trading.dto.response.SectorOverlapResponse;
import com.fynxt.trading.entity.PortfolioHolding;
import com.fynxt.trading.entity.Stock;
import com.fynxt.trading.entity.Trader;
import com.fynxt.trading.exception.ResourceNotFoundException;
import com.fynxt.trading.repository.PortfolioHoldingRepository;
import com.fynxt.trading.repository.StockRepository;
import com.fynxt.trading.repository.TraderRepository;
import com.fynxt.trading.repository.projection.HoldingPositionProjection;
import com.fynxt.trading.service.PortfolioService;
import com.fynxt.trading.util.SectorOverlapCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class PortfolioServiceImpl implements PortfolioService {

    private final TraderRepository traderRepository;
    private final StockRepository stockRepository;
    private final PortfolioHoldingRepository portfolioHoldingRepository;

    public PortfolioServiceImpl(
            TraderRepository traderRepository,
            StockRepository stockRepository,
            PortfolioHoldingRepository portfolioHoldingRepository) {
        this.traderRepository = traderRepository;
        this.stockRepository = stockRepository;
        this.portfolioHoldingRepository = portfolioHoldingRepository;
    }

    /**
     * Endpoint 4: Get Portfolio
     *
     * Returns a JSON summary of the trader's holdings grouped by sector:
     * {
     *   "traderId": "T001",
     *   "positions": { "AAPL": 150, "TSLA": 80 },
     *   "sectorBreakdown": { "TECH": 230 }
     * }
     *
     * Performance & Design:
     * - Uses findAllByTraderId(traderId) with JOIN FETCH on stocks,
     *   executing a single optimized SQL query to completely avoid N+1 queries.
     * - Read-only transaction (@Transactional(readOnly = true)) to optimize Hibernate session flush.
     *
     * @param traderId ID of the trader to lookup
     * @return PortfolioResponse with stock positions and sector breakdown
     */
    @Override
    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(String traderId) {
        log.info("Fetching portfolio for traderId='{}'", traderId);

        // 1. Verify trader exists
        if (!traderRepository.existsById(traderId)) {
            log.warn("Portfolio lookup failed: Trader not found with traderId='{}'", traderId);
            throw new ResourceNotFoundException("Trader not found with id: " + traderId);
        }

        // 2. Query holdings using high-performance database projection
        List<HoldingPositionProjection> positionsList = portfolioHoldingRepository.findHoldingPositionsByTraderId(traderId);
        log.debug("Found {} active holding positions for trader '{}'", positionsList.size(), traderId);

        // 3. Aggregate positions and sector breakdown
        Map<String, Long> positions = new LinkedHashMap<>();
        Map<String, Long> sectorBreakdown = new LinkedHashMap<>();

        for (HoldingPositionProjection item : positionsList) {
            String symbol = item.getStockSymbol();
            String sector = item.getSector();
            long quantity = item.getQuantity() != null ? item.getQuantity() : 0L;

            positions.put(symbol, quantity);
            sectorBreakdown.merge(sector, quantity, Long::sum);
        }

        log.info("Successfully retrieved portfolio for trader '{}': {} positions, {} sectors",
                traderId, positions.size(), sectorBreakdown.size());

        return PortfolioResponse.builder()
                .traderId(traderId)
                .positions(positions)
                .sectorBreakdown(sectorBreakdown)
                .build();
    }

    /**
     * Endpoint 5: Sector Overlap Analysis
     *
     * Compare the trader's holdings against benchmark baskets:
     * - TECH_HEAVY: AAPL, MSFT, GOOGL, TSLA, NVDA
     * - FINANCE_HEAVY: JPM, GS, BAC, MS, WFC
     * - BALANCED: AAPL, JPM, XOM, JNJ, TSLA
     *
     * Overlap Formula:
     * Overlap = [ 2 * |common stocks| / (|portfolio| + |basket|) ] * 100
     *
     * Requirements Met:
     * - Must be pure Java — calculation engine has no DB calls and no framework dependencies.
     *
     * @param traderId ID of the trader to analyze
     * @return SectorOverlapResponse containing overlaps, dominant basket, and risk flag
     */
    @Override
    @Transactional(readOnly = true)
    public SectorOverlapResponse analyzeSectorOverlap(String traderId) {
        log.info("Analyzing sector overlap for traderId='{}'", traderId);

        // 1. Verify trader exists
        if (!traderRepository.existsById(traderId)) {
            log.warn("Sector overlap analysis failed: Trader not found with traderId='{}'", traderId);
            throw new ResourceNotFoundException("Trader not found with id: " + traderId);
        }

        // 2. Query distinct active stock symbols held by the trader
        List<HoldingPositionProjection> positionsList = portfolioHoldingRepository.findHoldingPositionsByTraderId(traderId);
        Set<String> distinctStocks = new HashSet<>();
        for (HoldingPositionProjection item : positionsList) {
            if (item.getQuantity() != null && item.getQuantity() > 0) {
                distinctStocks.add(item.getStockSymbol());
            }
        }

        log.debug("Trader '{}' holds {} distinct stocks: {}", traderId, distinctStocks.size(), distinctStocks);

        // 3. Delegate to pure Java calculation utility (zero DB calls, zero framework dependencies)
        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(distinctStocks);

        log.info("Sector overlap complete for trader '{}': dominantBasket={}, riskFlag={}",
                traderId, response.getDominantBasket(), response.getRiskFlag());

        return response;
    }

    /**
     * Endpoint 6: Add to Portfolio
     *
     * Directly adds stock holdings to a trader's portfolio via POST:
     * { "stock": "NVDA", "sector": "TECH", "quantity": 100 }
     *
     * Business Rules:
     * - Validates trader exists (or auto-onboards on first interaction).
     * - Validates stock exists in stock master table (or auto-registers with provided sector).
     * - Atomically increases existing holding quantity or creates a new holding entry.
     * - Returns the updated PortfolioResponse.
     *
     * @param traderId the trader ID
     * @param request AddToPortfolioRequest containing stock, sector, and quantity
     * @return updated PortfolioResponse
     */
    @Override
    @Transactional
    public PortfolioResponse addToPortfolio(String traderId, AddToPortfolioRequest request) {
        log.info("Adding stock to portfolio: traderId='{}', stock='{}', sector='{}', quantity={}",
                traderId, request.getStock(), request.getSector(), request.getQuantity());

        // 1. Get or auto-onboard trader
        Trader trader = traderRepository.findById(traderId)
                .orElseGet(() -> {
                    log.info("Trader '{}' not found. Auto-onboarding trader.", traderId);
                    Trader newTrader = new Trader();
                    newTrader.setTraderId(traderId);
                    return traderRepository.save(newTrader);
                });

        // 2. Validate or register stock in stock master data
        Stock stock = stockRepository.findById(request.getStock())
                .orElseGet(() -> {
                    log.info("Stock '{}' not found in master data. Registering with sector '{}'",
                            request.getStock(), request.getSector());
                    Stock newStock = new Stock();
                    newStock.setStockSymbol(request.getStock());
                    newStock.setStockName(request.getStock());
                    newStock.setSector(request.getSector());
                    return stockRepository.save(newStock);
                });

        // 3. Atomically add to existing holding or create a new holding
        PortfolioHolding holding = portfolioHoldingRepository
                .findByTraderIdAndStockSymbol(trader.getTraderId(), stock.getStockSymbol())
                .orElse(null);

        if (holding == null) {
            log.info("Creating new portfolio holding for trader '{}', stock '{}', quantity={}",
                    trader.getTraderId(), stock.getStockSymbol(), request.getQuantity());
            holding = new PortfolioHolding();
            holding.setTrader(trader);
            holding.setStock(stock);
            holding.setQuantity(request.getQuantity());
        } else {
            long currentQty = holding.getQuantity() != null ? holding.getQuantity() : 0L;
            long newQty = currentQty + request.getQuantity();
            log.info("Updating existing portfolio holding for trader '{}', stock '{}': {} -> {}",
                    trader.getTraderId(), stock.getStockSymbol(), currentQty, newQty);
            holding.setQuantity(newQty);
        }

        portfolioHoldingRepository.save(holding);

        log.info("Successfully added {} shares of '{}' to trader '{}'",
                request.getQuantity(), stock.getStockSymbol(), traderId);

        return getPortfolio(traderId);
    }
}
