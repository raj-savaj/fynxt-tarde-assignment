package com.fynxt.trading.util;

import com.fynxt.trading.dto.response.BasketOverlapItem;
import com.fynxt.trading.dto.response.SectorOverlapResponse;
import com.fynxt.trading.enums.BenchmarkBasket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sector Overlap Analysis.
 *
 * Characteristics:
 * - Stateless & Thread-Safe: Contains pure mathematical and analytical functions.
 * - Zero Framework / DB Dependencies: Satisfies strict assignment requirement.
 * - Static Utility Pattern: Can be invoked directly without object allocation overhead.
 *
 * Overlap Formula:
 * Overlap = [ 2 * |common stocks| / (|portfolio| + |basket|) ] * 100
 *
 * Risk Flag Thresholds:
 * - >= 60% -> HIGH
 * - >= 40% -> MEDIUM
 * - < 40%  -> LOW
 */
public final class SectorOverlapCalculator {

    // Private constructor to prevent instantiation of utility class
    private SectorOverlapCalculator() {
    }

    /**
     * Calculates overlap percentages between the given portfolio stocks and benchmark baskets.
     *
     * @param portfolioStocks set of distinct stock symbols in the trader's portfolio
     * @return SectorOverlapResponse containing overlaps, dominant basket, and risk flag
     */
    public static SectorOverlapResponse calculateOverlap(Set<String> portfolioStocks) {
        Set<String> safePortfolio = portfolioStocks == null ? Set.of() : portfolioStocks;
        int portfolioSize = safePortfolio.size();

        List<BasketOverlapItem> overlaps = new ArrayList<>();
        double highestOverlap = -1.0;
        String dominantBasket = null;

        for (BenchmarkBasket basket : BenchmarkBasket.values()) {
            Set<String> basketStocks = basket.getStocks();
            int basketSize = basketStocks.size();

            // Find intersection: common stocks
            Set<String> common = new HashSet<>(safePortfolio);
            common.retainAll(basketStocks);
            int commonCount = common.size();

            // Formula: Overlap = [ 2 * |common stocks| / (|portfolio| + |basket|) ] * 100
            double overlapPercent = 0.0;
            int totalDenominator = portfolioSize + basketSize;
            if (totalDenominator > 0) {
                overlapPercent = ((2.0 * commonCount) / totalDenominator) * 100.0;
            }

            // Track dominant basket (highest overlap)
            if (overlapPercent > highestOverlap) {
                highestOverlap = overlapPercent;
                dominantBasket = basket.name();
            }

            overlaps.add(BasketOverlapItem.builder()
                    .basket(basket.name())
                    .overlap(String.format(Locale.US, "%.2f%%", overlapPercent))
                    .build());
        }

        // Determine risk flag
        String riskFlag;
        if (highestOverlap >= 60.0) {
            riskFlag = "HIGH";
        } else if (highestOverlap >= 40.0) {
            riskFlag = "MEDIUM";
        } else {
            riskFlag = "LOW";
        }

        return SectorOverlapResponse.builder()
                .overlaps(overlaps)
                .dominantBasket(dominantBasket)
                .riskFlag(riskFlag)
                .build();
    }
}
