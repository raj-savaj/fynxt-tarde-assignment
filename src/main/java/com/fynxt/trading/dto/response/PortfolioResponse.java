package com.fynxt.trading.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioResponse {

    private String traderId;

    /**
     * Map of stock symbol to held share count.
     * Example: { "AAPL": 150, "TSLA": 80 }
     */
    private Map<String, Long> positions;

    /**
     * Map of sector name to total aggregated share count across holdings.
     * Example: { "TECH": 230 }
     */
    private Map<String, Long> sectorBreakdown;
}
