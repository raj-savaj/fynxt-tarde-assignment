package com.fynxt.trading.service;

import com.fynxt.trading.dto.request.AddToPortfolioRequest;
import com.fynxt.trading.dto.response.PortfolioResponse;
import com.fynxt.trading.dto.response.SectorOverlapResponse;

public interface PortfolioService {

    /**
     * Endpoint 4: Get Portfolio
     * Retrieves the trader's stock holdings grouped by sector.
     *
     * @param traderId the trader identifier (e.g. "T001")
     * @return PortfolioResponse containing positions and sector breakdown
     */
    PortfolioResponse getPortfolio(String traderId);

    /**
     * Endpoint 5: Sector Overlap Analysis
     * Compares the trader's active holdings against benchmark baskets using pure Java logic.
     *
     * @param traderId the trader identifier
     * @return SectorOverlapResponse with overlap percentages, dominant basket, and risk flag
     */
    SectorOverlapResponse analyzeSectorOverlap(String traderId);

    /**
     * Endpoint 6: Add to Portfolio
     * Directly adds stock holdings to a trader's portfolio.
     *
     * @param traderId the trader identifier
     * @param request AddToPortfolioRequest containing stock, sector, and quantity
     * @return updated PortfolioResponse
     */
    PortfolioResponse addToPortfolio(String traderId, com.fynxt.trading.dto.request.AddToPortfolioRequest request);
}
