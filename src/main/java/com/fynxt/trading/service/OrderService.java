package com.fynxt.trading.service;

import com.fynxt.trading.dto.request.PlaceOrderRequest;
import com.fynxt.trading.dto.response.OrderResponse;

public interface OrderService {

    /**
     * Places a new BUY or SELL order for a trader in PENDING status.
     * Enforces:
     * 1. Trader maximum 3 pending orders rule.
     * 2. Share availability check for SELL orders.
     *
     * @param request the order placement details
     * @return the saved order details
     */
    OrderResponse placeOrder(PlaceOrderRequest request);

    /**
     * Endpoint 2: Fills a PENDING order.
     * Enforces:
     * 1. Only a PENDING order can be filled.
     * 2. BUY increases the holding; SELL decreases it.
     *
     * @param orderId ID of the order to fill
     * @return the filled order response
     */
    OrderResponse fillOrder(Long orderId);

    /**
     * Endpoint 3: Cancels a PENDING order.
     * Enforces:
     * 1. Only a PENDING order can be cancelled.
     * 2. Any other state returns a descriptive error response.
     *
     * @param orderId ID of the order to cancel
     * @return the cancelled order response
     */
    OrderResponse cancelOrder(Long orderId);
}
