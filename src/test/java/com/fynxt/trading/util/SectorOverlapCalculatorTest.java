package com.fynxt.trading.util;

import com.fynxt.trading.dto.response.BasketOverlapItem;
import com.fynxt.trading.dto.response.SectorOverlapResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for SectorOverlapCalculator covering business logic, the PDF worked example, and edge cases.
 */
class SectorOverlapCalculatorTest {

    // ==========================================
    // Business Logic Tests (PDF Worked Example)
    // ==========================================

    @Test
    @DisplayName("Business Logic: Exactly matches PDF Page 3 worked example for HIGH risk")
    void testWorkedExample_HighRisk() {
        // From PDF Page 3: Trader's portfolio: [AAPL, TSLA, NVDA] (3 stocks)
        Set<String> portfolio = Set.of("AAPL", "TSLA", "NVDA");

        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(portfolio);

        assertNotNull(response);
        assertEquals("TECH_HEAVY", response.getDominantBasket());
        assertEquals("HIGH", response.getRiskFlag());

        Map<String, String> overlapMap = response.getOverlaps().stream()
                .collect(Collectors.toMap(BasketOverlapItem::getBasket, BasketOverlapItem::getOverlap));

        // vs TECH_HEAVY: [2 * 3 / (3 + 5)] * 100 = 75.00%
        assertEquals("75.00%", overlapMap.get("TECH_HEAVY"));
        // vs BALANCED: [2 * 2 / (3 + 5)] * 100 = 50.00%
        assertEquals("50.00%", overlapMap.get("BALANCED"));
        // vs FINANCE_HEAVY: [2 * 0 / (3 + 5)] * 100 = 0.00%
        assertEquals("0.00%", overlapMap.get("FINANCE_HEAVY"));
    }

    // ==========================================
    // Edge Cases Tests
    // ==========================================

    @Test
    @DisplayName("Edge Case: Returns MEDIUM risk when highest overlap is between 40% and 59%")
    void testMediumRisk_Threshold() {
        // Portfolio: [JPM, GS] -> 2 stocks
        // vs FINANCE_HEAVY (5 stocks): 2 common -> [2 * 2 / (2 + 5)] * 100 = 57.14%
        Set<String> portfolio = Set.of("JPM", "GS");

        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(portfolio);

        assertNotNull(response);
        assertEquals("FINANCE_HEAVY", response.getDominantBasket());
        assertEquals("MEDIUM", response.getRiskFlag());

        Map<String, String> overlapMap = response.getOverlaps().stream()
                .collect(Collectors.toMap(BasketOverlapItem::getBasket, BasketOverlapItem::getOverlap));
        assertEquals("57.14%", overlapMap.get("FINANCE_HEAVY"));
    }

    @Test
    @DisplayName("Edge Case: Empty portfolio returns 0.00% overlap for all baskets and LOW risk")
    void testEmptyPortfolio_ReturnsZeroOverlap_LowRisk() {
        Set<String> emptyPortfolio = Set.of();

        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(emptyPortfolio);

        assertNotNull(response);
        assertEquals("LOW", response.getRiskFlag());

        for (BasketOverlapItem item : response.getOverlaps()) {
            assertEquals("0.00%", item.getOverlap());
        }
    }

    @Test
    @DisplayName("Edge Case: Null portfolio is handled gracefully without NullPointerException")
    void testNullPortfolio_HandledGracefully() {
        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(null);

        assertNotNull(response);
        assertEquals("LOW", response.getRiskFlag());

        for (BasketOverlapItem item : response.getOverlaps()) {
            assertEquals("0.00%", item.getOverlap());
        }
    }

    @Test
    @DisplayName("Edge Case: Portfolio with non-benchmark stocks produces 0.00% overlap and LOW risk")
    void testNoCommonStocks_ReturnsZeroOverlap_LowRisk() {
        Set<String> nonBenchmarkStocks = Set.of("UNKNOWN_STOCK_A", "UNKNOWN_STOCK_B");

        SectorOverlapResponse response = SectorOverlapCalculator.calculateOverlap(nonBenchmarkStocks);

        assertNotNull(response);
        assertEquals("LOW", response.getRiskFlag());

        for (BasketOverlapItem item : response.getOverlaps()) {
            assertEquals("0.00%", item.getOverlap());
        }
    }
}
