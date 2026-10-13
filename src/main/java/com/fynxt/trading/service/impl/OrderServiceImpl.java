package com.fynxt.trading.service.impl;

import com.fynxt.trading.dto.request.PlaceOrderRequest;
import com.fynxt.trading.dto.response.OrderResponse;
import com.fynxt.trading.entity.PortfolioHolding;
import com.fynxt.trading.entity.Stock;
import com.fynxt.trading.entity.TradeOrder;
import com.fynxt.trading.entity.Trader;
import com.fynxt.trading.enums.OrderSide;
import com.fynxt.trading.enums.OrderStatus;
import com.fynxt.trading.exception.InsufficientSharesException;
import com.fynxt.trading.exception.InvalidOrderStateException;
import com.fynxt.trading.exception.OrderLimitExceededException;
import com.fynxt.trading.exception.ResourceNotFoundException;
import com.fynxt.trading.repository.PortfolioHoldingRepository;
import com.fynxt.trading.repository.StockRepository;
import com.fynxt.trading.repository.TradeOrderRepository;
import com.fynxt.trading.repository.TraderRepository;
import com.fynxt.trading.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final int MAX_PENDING_ORDERS_PER_TRADER = 3;

    private final TraderRepository traderRepository;
    private final StockRepository stockRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PortfolioHoldingRepository portfolioHoldingRepository;

    public OrderServiceImpl(
            TraderRepository traderRepository,
            StockRepository stockRepository,
            TradeOrderRepository tradeOrderRepository,
            PortfolioHoldingRepository portfolioHoldingRepository) {
        this.traderRepository = traderRepository;
        this.stockRepository = stockRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.portfolioHoldingRepository = portfolioHoldingRepository;
    }

    /**
     * Places a new BUY or SELL order.
     *
     * Concurrency & Transaction Design:
     * - Runs inside a database transaction (@Transactional).
     * - Uses a pessimistic write lock (SELECT ... FOR UPDATE) on the trader's row
     *   via traderRepository.findByIdForUpdate. This serializes order placement for each
     *   individual trader, preventing race conditions where multiple concurrent requests
     *   could bypass the "max 3 pending orders" limit.
     *
     * Business Rules:
     * 1. A trader cannot have more than 3 orders in PENDING state at any given time.
     * 2. A SELL must be rejected if the trader does not hold enough shares.
     *
     * @param request PlaceOrderRequest containing traderId, stock, sector, quantity, and side
     * @return OrderResponse containing created order details with status PENDING
     */
    @Override
    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        log.info("Processing order placement: traderId='{}', stock='{}', side={}, quantity={}, sector='{}'",
                request.getTraderId(), request.getStock(), request.getSide(), request.getQuantity(), request.getSector());

        // 1. Acquire pessimistic lock on trader row (or onboard trader if first time seen)
        Trader trader = traderRepository.findByIdForUpdate(request.getTraderId())
                .orElseGet(() -> {
                    log.info("Trader '{}' not found in master table. Auto-onboarding trader.", request.getTraderId());
                    Trader newTrader = new Trader();
                    newTrader.setTraderId(request.getTraderId());
                    return traderRepository.save(newTrader);
                });

        // 2. Enforce Rule: A trader cannot have more than 3 orders in PENDING state at any given time
        long currentPendingOrders = tradeOrderRepository.countByTraderIdAndStatus(trader.getTraderId(), OrderStatus.PENDING);
        log.debug("Trader '{}' currently has {} PENDING orders (limit: {})",
                trader.getTraderId(), currentPendingOrders, MAX_PENDING_ORDERS_PER_TRADER);

        if (currentPendingOrders >= MAX_PENDING_ORDERS_PER_TRADER) {
            log.warn("Order placement rejected: Trader '{}' already has {} pending orders (max allowed: {})",
                    trader.getTraderId(), currentPendingOrders, MAX_PENDING_ORDERS_PER_TRADER);
            throw new OrderLimitExceededException(String.format(
                    "A trader cannot have more than %d orders in PENDING state at any given time. Current pending: %d",
                    MAX_PENDING_ORDERS_PER_TRADER, currentPendingOrders));
        }

        // 3. Enforce Rule: A SELL must be rejected if the trader does not hold enough shares
        if (request.getSide() == OrderSide.SELL) {
            PortfolioHolding holding = portfolioHoldingRepository
                    .findByTraderIdAndStockSymbol(trader.getTraderId(), request.getStock())
                    .orElse(null);

            long availableShares = (holding != null && holding.getQuantity() != null) ? holding.getQuantity() : 0L;
            log.debug("Checking holdings for SELL order: Trader '{}', stock='{}', required={}, available={}",
                    trader.getTraderId(), request.getStock(), request.getQuantity(), availableShares);

            if (availableShares < request.getQuantity()) {
                log.warn("SELL order rejected: Trader '{}' does not hold enough shares of '{}'. Required: {}, Available: {}",
                        trader.getTraderId(), request.getStock(), request.getQuantity(), availableShares);
                throw new InsufficientSharesException(String.format(
                        "Cannot place SELL order. Trader '%s' holds %d shares of '%s', but requested to sell %d shares.",
                        trader.getTraderId(), availableShares, request.getStock(), request.getQuantity()));
            }
        }

        // 4. Validate or register stock in master table
        Stock stock = stockRepository.findById(request.getStock())
                .orElseGet(() -> {
                    log.info("Stock '{}' not found in stock master table. Registering with sector '{}'",
                            request.getStock(), request.getSector());
                    Stock newStock = new Stock();
                    newStock.setStockSymbol(request.getStock());
                    newStock.setStockName(request.getStock());
                    newStock.setSector(request.getSector());
                    return stockRepository.save(newStock);
                });

        // 5. Construct and persist TradeOrder with initial state PENDING
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setTrader(trader);
        tradeOrder.setStock(stock);
        tradeOrder.setQuantity(request.getQuantity());
        tradeOrder.setSide(request.getSide());
        tradeOrder.setStatus(OrderStatus.PENDING);

        TradeOrder savedOrder = tradeOrderRepository.save(tradeOrder);

        log.info("Successfully placed order: orderId={}, traderId='{}', stock='{}', side={}, quantity={}, status={}",
                savedOrder.getOrderId(), trader.getTraderId(), stock.getStockSymbol(),
                savedOrder.getSide(), savedOrder.getQuantity(), savedOrder.getStatus());

        // 6. Return response DTO
        return mapToResponse(savedOrder);
    }

    /**
     * Endpoint 2: Fill an Order
     *
     * Business Rules:
     * - Only a PENDING order can be filled.
     * - BUY increases the holding; SELL decreases it.
     *
     * Concurrency & Locking:
     * - Uses pessimistic write lock (findByIdForUpdate) to lock the order record,
     *   preventing race conditions between concurrent fill/cancel calls.
     * - Runs inside a single database transaction (@Transactional) to ensure atomic
     *   order state transition and portfolio balance update.
     *
     * @param orderId ID of the order to fill
     * @return OrderResponse with status FILLED
     */
    @Override
    @Transactional
    public OrderResponse fillOrder(Long orderId) {
        log.info("Processing fill request for orderId={}", orderId);

        // 1. Lock and retrieve order row
        TradeOrder order = tradeOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> {
                    log.warn("Order fill failed: Order not found with orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Validate order state: Only PENDING order can be filled
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Order fill rejected: Order id={} is in {} state (only PENDING allowed)",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException(String.format(
                    "Cannot fill order with id %d. Only PENDING orders can be filled, but current status is %s.",
                    orderId, order.getStatus()));
        }

        Trader trader = order.getTrader();
        Stock stock = order.getStock();

        // 3. Update Portfolio Holding: BUY increases; SELL decreases
        if (order.getSide() == OrderSide.BUY) {
            PortfolioHolding holding = portfolioHoldingRepository
                    .findByTraderIdAndStockSymbol(trader.getTraderId(), stock.getStockSymbol())
                    .orElse(null);

            if (holding == null) {
                log.info("Creating new portfolio holding row for trader '{}' and stock '{}' with quantity={}",
                        trader.getTraderId(), stock.getStockSymbol(), order.getQuantity());
                holding = new PortfolioHolding();
                holding.setTrader(trader);
                holding.setStock(stock);
                holding.setQuantity(order.getQuantity());
            } else {
                long currentQty = holding.getQuantity() != null ? holding.getQuantity() : 0L;
                long updatedQty = currentQty + order.getQuantity();
                log.info("Updating portfolio holding for trader '{}', stock '{}': {} -> {}",
                        trader.getTraderId(), stock.getStockSymbol(), currentQty, updatedQty);
                holding.setQuantity(updatedQty);
            }
            portfolioHoldingRepository.save(holding);

        } else if (order.getSide() == OrderSide.SELL) {
            PortfolioHolding holding = portfolioHoldingRepository
                    .findByTraderIdAndStockSymbol(trader.getTraderId(), stock.getStockSymbol())
                    .orElseThrow(() -> {
                        log.warn("SELL fill failed: Trader '{}' has no holding for stock '{}'",
                                trader.getTraderId(), stock.getStockSymbol());
                        return new InsufficientSharesException(String.format(
                                "Cannot fill SELL order. Trader '%s' holds no shares of '%s'.",
                                trader.getTraderId(), stock.getStockSymbol()));
                    });

            long currentQty = holding.getQuantity() != null ? holding.getQuantity() : 0L;
            if (currentQty < order.getQuantity()) {
                log.warn("SELL fill failed: Trader '{}' holds {} shares of '{}', but order requires {}",
                        trader.getTraderId(), currentQty, stock.getStockSymbol(), order.getQuantity());
                throw new InsufficientSharesException(String.format(
                        "Cannot fill SELL order. Trader '%s' holds %d shares of '%s', but order quantity is %d.",
                        trader.getTraderId(), currentQty, stock.getStockSymbol(), order.getQuantity()));
            }

            long updatedQty = currentQty - order.getQuantity();
            log.info("Decreasing portfolio holding for trader '{}', stock '{}': {} -> {}",
                    trader.getTraderId(), stock.getStockSymbol(), currentQty, updatedQty);
            holding.setQuantity(updatedQty);
            portfolioHoldingRepository.save(holding);
        }

        // 4. Transition order status: PENDING -> FILLED
        order.setStatus(OrderStatus.FILLED);
        TradeOrder updatedOrder = tradeOrderRepository.save(order);

        log.info("Successfully FILLED order id={} for trader '{}', stock '{}', quantity={}",
                updatedOrder.getOrderId(), trader.getTraderId(), stock.getStockSymbol(), updatedOrder.getQuantity());

        return mapToResponse(updatedOrder);
    }

    /**
     * Endpoint 3: Cancel an Order
     *
     * Business Rules:
     * - Only a PENDING order can be cancelled.
     * - Any other state should return a clear, descriptive error response.
     *
     * Concurrency & Locking:
     * - Uses pessimistic write lock (findByIdForUpdate) to atomically transition
     *   only PENDING orders, safely preventing concurrent fill/cancel races.
     *
     * @param orderId ID of the order to cancel
     * @return OrderResponse with status CANCELLED
     */
    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        log.info("Processing cancel request for orderId={}", orderId);

        // 1. Lock and retrieve order row
        TradeOrder order = tradeOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> {
                    log.warn("Order cancellation failed: Order not found with orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Validate order state: Only PENDING order can be cancelled
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Order cancel rejected: Order id={} is in {} state (only PENDING allowed)",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException(String.format(
                    "Cannot cancel order with id %d. Only PENDING orders can be cancelled, but current status is %s.",
                    orderId, order.getStatus()));
        }

        // 3. Transition order status: PENDING -> CANCELLED
        order.setStatus(OrderStatus.CANCELLED);
        TradeOrder updatedOrder = tradeOrderRepository.save(order);

        log.info("Successfully CANCELLED order id={} for trader '{}'",
                updatedOrder.getOrderId(), order.getTrader().getTraderId());

        return mapToResponse(updatedOrder);
    }

    private OrderResponse mapToResponse(TradeOrder order) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .traderId(order.getTrader() != null ? order.getTrader().getTraderId() : null)
                .stock(order.getStock() != null ? order.getStock().getStockSymbol() : null)
                .sector(order.getStock() != null ? order.getStock().getSector() : null)
                .quantity(order.getQuantity())
                .side(order.getSide())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
