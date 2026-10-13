package com.fynxt.trading.service;

import com.fynxt.trading.dto.request.AddToPortfolioRequest;
import com.fynxt.trading.dto.response.PortfolioResponse;
import com.fynxt.trading.entity.PortfolioHolding;
import com.fynxt.trading.entity.Stock;
import com.fynxt.trading.entity.Trader;
import com.fynxt.trading.exception.ResourceNotFoundException;
import com.fynxt.trading.repository.PortfolioHoldingRepository;
import com.fynxt.trading.repository.StockRepository;
import com.fynxt.trading.repository.TraderRepository;
import com.fynxt.trading.repository.projection.HoldingPositionProjection;
import com.fynxt.trading.service.impl.PortfolioServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PortfolioServiceImpl covering business logic and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceImplTest {

    @Mock
    private TraderRepository traderRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private PortfolioHoldingRepository portfolioHoldingRepository;

    @InjectMocks
    private PortfolioServiceImpl portfolioService;

    private HoldingPositionProjection createProjection(String symbol, String sector, Long quantity) {
        return new HoldingPositionProjection() {
            @Override
            public String getStockSymbol() {
                return symbol;
            }

            @Override
            public String getSector() {
                return sector;
            }

            @Override
            public Long getQuantity() {
                return quantity;
            }
        };
    }

    // ==========================================
    // Business Logic Tests
    // ==========================================

    @Test
    @DisplayName("Business Logic: Get Portfolio returns positions and grouped sector breakdown")
    void testGetPortfolio_Success() {
        HoldingPositionProjection proj1 = createProjection("AAPL", "TECH", 150L);
        HoldingPositionProjection proj2 = createProjection("TSLA", "TECH", 80L);
        HoldingPositionProjection proj3 = createProjection("JPM", "FINANCE", 50L);

        when(traderRepository.existsById("T001")).thenReturn(true);
        when(portfolioHoldingRepository.findHoldingPositionsByTraderId("T001"))
                .thenReturn(List.of(proj1, proj2, proj3));

        PortfolioResponse response = portfolioService.getPortfolio("T001");

        assertNotNull(response);
        assertEquals("T001", response.getTraderId());
        assertEquals(3, response.getPositions().size());
        assertEquals(150L, response.getPositions().get("AAPL"));
        assertEquals(80L, response.getPositions().get("TSLA"));
        assertEquals(50L, response.getPositions().get("JPM"));

        // Sector breakdown: TECH = 150 + 80 = 230, FINANCE = 50
        assertEquals(230L, response.getSectorBreakdown().get("TECH"));
        assertEquals(50L, response.getSectorBreakdown().get("FINANCE"));
    }

    @Test
    @DisplayName("Business Logic: Add to Portfolio increases existing stock holding atomically")
    void testAddToPortfolio_ExistingHolding_IncreasesQuantity() {
        AddToPortfolioRequest request = AddToPortfolioRequest.builder()
                .stock("AAPL")
                .sector("TECH")
                .quantity(50L)
                .build();

        Trader trader = new Trader();
        trader.setTraderId("T001");

        Stock stock = new Stock();
        stock.setStockSymbol("AAPL");
        stock.setSector("TECH");

        PortfolioHolding existingHolding = new PortfolioHolding();
        existingHolding.setTrader(trader);
        existingHolding.setStock(stock);
        existingHolding.setQuantity(100L);

        when(traderRepository.findById("T001")).thenReturn(Optional.of(trader));
        when(stockRepository.findById("AAPL")).thenReturn(Optional.of(stock));
        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL"))
                .thenReturn(Optional.of(existingHolding));

        HoldingPositionProjection updatedProj = createProjection("AAPL", "TECH", 150L);
        when(traderRepository.existsById("T001")).thenReturn(true);
        when(portfolioHoldingRepository.findHoldingPositionsByTraderId("T001"))
                .thenReturn(List.of(updatedProj));

        PortfolioResponse response = portfolioService.addToPortfolio("T001", request);

        assertNotNull(response);
        assertEquals(150L, existingHolding.getQuantity());
        verify(portfolioHoldingRepository).save(existingHolding);
    }

    // ==========================================
    // Edge Cases Tests
    // ==========================================

    @Test
    @DisplayName("Edge Case: Get Portfolio throws ResourceNotFoundException when trader does not exist")
    void testGetPortfolio_TraderNotFound_ThrowsException() {
        when(traderRepository.existsById("NON_EXISTENT")).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> portfolioService.getPortfolio("NON_EXISTENT"));
    }

    @Test
    @DisplayName("Edge Case: Add to Portfolio automatically onboards new trader and registers new stock")
    void testAddToPortfolio_AutoOnboardsNewTraderAndStock() {
        AddToPortfolioRequest request = AddToPortfolioRequest.builder()
                .stock("NVDA")
                .sector("TECH")
                .quantity(75L)
                .build();

        when(traderRepository.findById("NEW_TRADER")).thenReturn(Optional.empty());
        when(traderRepository.save(any(Trader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(stockRepository.findById("NVDA")).thenReturn(Optional.empty());
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("NEW_TRADER", "NVDA"))
                .thenReturn(Optional.empty());

        HoldingPositionProjection p1 = createProjection("NVDA", "TECH", 75L);
        when(traderRepository.existsById("NEW_TRADER")).thenReturn(true);
        when(portfolioHoldingRepository.findHoldingPositionsByTraderId("NEW_TRADER"))
                .thenReturn(List.of(p1));

        PortfolioResponse response = portfolioService.addToPortfolio("NEW_TRADER", request);

        assertNotNull(response);
        assertEquals("NEW_TRADER", response.getTraderId());
        assertEquals(75L, response.getPositions().get("NVDA"));
        verify(traderRepository).save(any(Trader.class));
        verify(stockRepository).save(any(Stock.class));
        verify(portfolioHoldingRepository).save(any(PortfolioHolding.class));
    }
}
