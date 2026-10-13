package com.fynxt.trading.service;

import com.fynxt.trading.dto.request.PlaceOrderRequest;
import com.fynxt.trading.dto.response.OrderResponse;
import com.fynxt.trading.entity.PortfolioHolding;
import com.fynxt.trading.entity.Stock;
import com.fynxt.trading.entity.TradeOrder;
import com.fynxt.trading.entity.Trader;
import com.fynxt.trading.enums.OrderSide;
import com.fynxt.trading.enums.OrderStatus;
import com.fynxt.trading.exception.InvalidOrderStateException;
import com.fynxt.trading.exception.OrderLimitExceededException;
import com.fynxt.trading.repository.PortfolioHoldingRepository;
import com.fynxt.trading.repository.StockRepository;
import com.fynxt.trading.repository.TradeOrderRepository;
import com.fynxt.trading.repository.TraderRepository;
import com.fynxt.trading.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Concurrency Test Suite for OrderService.
 *
 * Verifies multi-threaded safety and race condition prevention:
 * 1. Concurrent order placement enforces the "Max 3 PENDING orders" rule under contention.
 * 2. Concurrent Fill vs Cancel race condition ensures only one terminal state transition succeeds.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceConcurrencyTest {

    @Mock
    private TraderRepository traderRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private TradeOrderRepository tradeOrderRepository;

    @Mock
    private PortfolioHoldingRepository portfolioHoldingRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Trader testTrader;
    private Stock testStock;

    @BeforeEach
    void setUp() {
        testTrader = new Trader();
        testTrader.setTraderId("T001");
        testTrader.setCreatedAt(Instant.now());

        testStock = new Stock();
        testStock.setStockSymbol("AAPL");
        testStock.setStockName("Apple Inc.");
        testStock.setSector("TECH");
    }

    @Test
    @DisplayName("Concurrency: 5 simultaneous order placements strictly enforce max 3 PENDING limit")
    void testConcurrentOrderPlacement_EnforcesMaxPendingLimit() throws Exception {
        int totalThreads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger pendingOrdersCount = new AtomicInteger(0);
        ReentrantLock traderLock = new ReentrantLock();

        // Simulate pessimistic database locking (SELECT ... FOR UPDATE on Trader)
        when(traderRepository.findByIdForUpdate("T001")).thenAnswer(invocation -> {
            traderLock.lock();
            return Optional.of(testTrader);
        });

        when(tradeOrderRepository.countByTraderIdAndStatus("T001", OrderStatus.PENDING))
                .thenAnswer(invocation -> (long) pendingOrdersCount.get());

        when(stockRepository.findById("AAPL")).thenReturn(Optional.of(testStock));

        when(tradeOrderRepository.save(any(TradeOrder.class))).thenAnswer(invocation -> {
            try {
                TradeOrder order = invocation.getArgument(0);
                order.setOrderId((long) pendingOrdersCount.incrementAndGet());
                return order;
            } finally {
                traderLock.unlock();
            }
        });

        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            results.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(); // Ensure all threads start simultaneously

                try {
                    PlaceOrderRequest request = PlaceOrderRequest.builder()
                            .traderId("T001")
                            .stock("AAPL")
                            .sector("TECH")
                            .quantity(10L)
                            .side(OrderSide.BUY)
                            .build();

                    OrderResponse response = orderService.placeOrder(request);
                    return "SUCCESS:" + response.getOrderId();
                } catch (OrderLimitExceededException e) {
                    // Unlock if exception was thrown after locking
                    if (traderLock.isHeldByCurrentThread()) {
                        traderLock.unlock();
                    }
                    return "REJECTED_LIMIT_EXCEEDED";
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release threads concurrently

        int successCount = 0;
        int rejectedCount = 0;

        for (Future<String> future : results) {
            String outcome = future.get(5, TimeUnit.SECONDS);
            if (outcome.startsWith("SUCCESS")) {
                successCount++;
            } else if (outcome.equals("REJECTED_LIMIT_EXCEEDED")) {
                rejectedCount++;
            }
        }

        executor.shutdown();

        // Exactly 3 orders must succeed (limit = 3), remaining 2 must be rejected
        assertEquals(3, successCount, "Exactly 3 orders should succeed before limit is reached");
        assertEquals(2, rejectedCount, "Exactly 2 orders should be rejected due to pending limit");
    }

    @Test
    @DisplayName("Concurrency: Simultaneous Fill and Cancel race condition allows only one terminal transition")
    void testConcurrentFillAndCancel_OnlyOneTerminalStateSucceeds() throws Exception {
        TradeOrder sharedOrder = new TradeOrder();
        sharedOrder.setOrderId(100L);
        sharedOrder.setTrader(testTrader);
        sharedOrder.setStock(testStock);
        sharedOrder.setQuantity(50L);
        sharedOrder.setSide(OrderSide.BUY);
        sharedOrder.setStatus(OrderStatus.PENDING);

        PortfolioHolding holding = new PortfolioHolding();
        holding.setHoldingId(1L);
        holding.setTrader(testTrader);
        holding.setStock(testStock);
        holding.setQuantity(100L);

        ReentrantLock orderLock = new ReentrantLock();

        // Simulate pessimistic locking on the TradeOrder row
        when(tradeOrderRepository.findByIdForUpdate(100L)).thenAnswer(invocation -> {
            orderLock.lock();
            return Optional.of(sharedOrder);
        });

        when(tradeOrderRepository.save(any(TradeOrder.class))).thenAnswer(invocation -> {
            try {
                return invocation.getArgument(0);
            } finally {
                orderLock.unlock();
            }
        });

        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL"))
                .thenReturn(Optional.of(holding));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Thread 1 attempts FILL
        Future<String> fillFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            try {
                orderService.fillOrder(100L);
                return "FILL_SUCCESS";
            } catch (InvalidOrderStateException e) {
                if (orderLock.isHeldByCurrentThread()) {
                    orderLock.unlock();
                }
                return "FILL_REJECTED";
            }
        });

        // Thread 2 attempts CANCEL
        Future<String> cancelFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            try {
                orderService.cancelOrder(100L);
                return "CANCEL_SUCCESS";
            } catch (InvalidOrderStateException e) {
                if (orderLock.isHeldByCurrentThread()) {
                    orderLock.unlock();
                }
                return "CANCEL_REJECTED";
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire both threads concurrently

        String fillResult = fillFuture.get(5, TimeUnit.SECONDS);
        String cancelResult = cancelFuture.get(5, TimeUnit.SECONDS);

        executor.shutdown();

        // Exactly one terminal state succeeds, the other is rejected with InvalidOrderStateException
        boolean oneSucceededAndOneFailed =
                (fillResult.equals("FILL_SUCCESS") && cancelResult.equals("CANCEL_REJECTED")) ||
                (cancelResult.equals("CANCEL_SUCCESS") && fillResult.equals("FILL_REJECTED"));

        assertEquals(true, oneSucceededAndOneFailed, "Only one concurrent state transition must succeed");
    }
}
