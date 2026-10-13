package com.fynxt.trading.controller;

import com.fynxt.trading.dto.request.PlaceOrderRequest;
import com.fynxt.trading.dto.response.OrderResponse;
import com.fynxt.trading.service.OrderService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping({"/api/orders"})
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Place an Order
     *
     * Accepts a POST request with:
     * {
     *   "traderId": "T001",
     *   "stock": "AAPL",
     *   "sector": "TECH",
     *   "quantity": 50,
     *   "side": "BUY"
     * }
     *
     * Business Rules Enforced:
     * - A trader cannot have more than 3 orders in PENDING state at any given time.
     * - A SELL must be rejected if the trader does not hold enough shares.
     *
     * @param request PlaceOrderRequest containing order parameters
     * @return ResponseEntity with HttpStatus.CREATED (201) and OrderResponse payload
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        log.info("REST request to place an order: traderId={}, stock={}, side={}, quantity={}",
                request.getTraderId(), request.getStock(), request.getSide(), request.getQuantity());

        OrderResponse response = orderService.placeOrder(request);

        log.info("Order successfully created with orderId={}", response.getOrderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Fill an Order
     *
     * Business Rules:
     * - Only a PENDING order can be filled.
     * - BUY increases the holding; SELL decreases it.
     *
     * @param orderId ID of the order to fill
     * @return ResponseEntity with HttpStatus.OK (200) and filled OrderResponse
     */
    @PostMapping("/{orderId}/fill")
    public ResponseEntity<OrderResponse> fillOrder(@PathVariable("orderId") Long orderId) {
        log.info("REST request to fill order id={}", orderId);
        OrderResponse response = orderService.fillOrder(orderId);
        log.info("Order successfully filled: orderId={}, status={}", response.getOrderId(), response.getStatus());
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel an Order
     *
     * Business Rules:
     * - Only a PENDING order can be cancelled.
     * - Any other state should return a clear, descriptive error response.
     *
     * @param orderId ID of the order to cancel
     * @return ResponseEntity with HttpStatus.OK (200) and cancelled OrderResponse
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable("orderId") Long orderId) {
        log.info("REST request to cancel order id={}", orderId);
        OrderResponse response = orderService.cancelOrder(orderId);
        log.info("Order successfully cancelled: orderId={}, status={}", response.getOrderId(), response.getStatus());
        return ResponseEntity.ok(response);
    }
}
