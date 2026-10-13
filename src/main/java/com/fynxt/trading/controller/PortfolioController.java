package com.fynxt.trading.controller;

import com.fynxt.trading.dto.request.AddToPortfolioRequest;
import com.fynxt.trading.dto.response.PortfolioResponse;
import com.fynxt.trading.dto.response.SectorOverlapResponse;
import com.fynxt.trading.service.PortfolioService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping({"/api/portfolio"})
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Get Portfolio
     *
     * Returns a JSON summary of the trader's holdings grouped by sector:
     * {
     *   "traderId": "T001",
     *   "positions": { "AAPL": 150, "TSLA": 80 },
     *   "sectorBreakdown": { "TECH": 230 }
     * }
     *
     * @param traderId the trader identifier
     * @return ResponseEntity with HttpStatus.OK (200) and PortfolioResponse body
     */
    @GetMapping("/{traderId}")
    public ResponseEntity<PortfolioResponse> getPortfolio(@PathVariable("traderId") String traderId) {
        log.info("REST request to get portfolio for traderId='{}'", traderId);
        PortfolioResponse response = portfolioService.getPortfolio(traderId);
        log.info("Successfully returned portfolio for traderId='{}'", traderId);
        return ResponseEntity.ok(response);
    }

    /**
     * Sector Overlap Analysis
     *
     * Compares the trader's holdings against benchmark baskets:
     * - TECH_HEAVY, FINANCE_HEAVY, BALANCED
     *
     * Sample API Response:
     * {
     *   "overlaps": [
     *     { "basket": "TECH_HEAVY", "overlap": "60.00%" },
     *     { "basket": "FINANCE_HEAVY", "overlap": "0.00%" },
     *     { "basket": "BALANCED", "overlap": "40.00%" }
     *   ],
     *   "dominantBasket": "TECH_HEAVY",
     *   "riskFlag": "HIGH"
     * }
     *
     * @param traderId the trader identifier
     * @return ResponseEntity with HttpStatus.OK (200) and SectorOverlapResponse body
     */
    @GetMapping("/{traderId}/overlap")
    public ResponseEntity<SectorOverlapResponse> getSectorOverlap(@PathVariable("traderId") String traderId) {
        log.info("REST request to analyze sector overlap for traderId='{}'", traderId);
        SectorOverlapResponse response = portfolioService.analyzeSectorOverlap(traderId);
        log.info("Successfully calculated sector overlap for traderId='{}': dominantBasket={}, riskFlag={}",
                traderId, response.getDominantBasket(), response.getRiskFlag());
        return ResponseEntity.ok(response);
    }

    /**
     * Add to Portfolio
     *
     * Directly add stock holdings to a trader's portfolio via POST:
     * { "stock": "NVDA", "sector": "TECH", "quantity": 100 }
     *
     * @param traderId the trader identifier
     * @param request AddToPortfolioRequest containing stock, sector, and quantity
     * @return ResponseEntity with HttpStatus.OK (200) and updated PortfolioResponse
     */
    @PostMapping("/{traderId}/add")
    public ResponseEntity<PortfolioResponse> addToPortfolio(
            @PathVariable("traderId") String traderId,
            @Valid @RequestBody AddToPortfolioRequest request) {
        log.info("REST request to add stock to portfolio: traderId='{}', stock='{}', quantity={}",
                traderId, request.getStock(), request.getQuantity());
        PortfolioResponse response = portfolioService.addToPortfolio(traderId, request);
        log.info("Successfully added stock to portfolio for traderId='{}'", traderId);
        return ResponseEntity.ok(response);
    }
}
