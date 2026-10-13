package com.fynxt.trading.repository.projection;

/**
 * Spring Data JPA Projection for ultra-fast, read-only portfolio queries.
 * Selects only the required fields directly from the database without hydrating full entities.
 */
public interface HoldingPositionProjection {
    String getStockSymbol();
    String getSector();
    Long getQuantity();
}
