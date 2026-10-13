package com.fynxt.trading.service;

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
import com.fynxt.trading.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OrderServiceImpl covering business logic and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

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

    // ==========================================
    // Business Logic Tests
    // ==========================================

    @Test
    @DisplayName("Business Logic: Successfully places a BUY order in PENDING status")
    void testPlaceBuyOrder_Success() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .traderId("T001")
                .stock("AAPL")
                .sector("TECH")
                .quantity(50L)
                .side(OrderSide.BUY)
                .build();

        when(traderRepository.findByIdForUpdate("T001")).thenReturn(Optional.of(testTrader));
        when(tradeOrderRepository.countByTraderIdAndStatus("T001", OrderStatus.PENDING)).thenReturn(0L);
        when(stockRepository.findById("AAPL")).thenReturn(Optional.of(testStock));
        when(tradeOrderRepository.save(any(TradeOrder.class))).thenAnswer(invocation -> {
            TradeOrder order = invocation.getArgument(0);
            order.setOrderId(1L);
            order.setCreatedAt(Instant.now());
            return order;
        });

        OrderResponse response = orderService.placeOrder(request);

        assertNotNull(response);
        assertEquals(1L, response.getOrderId());
        assertEquals("T001", response.getTraderId());
        assertEquals("AAPL", response.getStock());
        assertEquals(OrderStatus.PENDING, response.getStatus());
        verify(tradeOrderRepository).save(any(TradeOrder.class));
    }

    @Test
    @DisplayName("Business Logic: Fill BUY order transitions to FILLED and increases holding")
    void testFillBuyOrder_IncreasesHolding() {
        TradeOrder order = new TradeOrder();
        order.setOrderId(10L);
        order.setTrader(testTrader);
        order.setStock(testStock);
        order.setQuantity(50L);
        order.setSide(OrderSide.BUY);
        order.setStatus(OrderStatus.PENDING);

        PortfolioHolding holding = new PortfolioHolding();
        holding.setHoldingId(1L);
        holding.setTrader(testTrader);
        holding.setStock(testStock);
        holding.setQuantity(100L);

        when(tradeOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL")).thenReturn(Optional.of(holding));
        when(tradeOrderRepository.save(any(TradeOrder.class))).thenReturn(order);

        OrderResponse response = orderService.fillOrder(10L);

        assertNotNull(response);
        assertEquals(OrderStatus.FILLED, response.getStatus());
        assertEquals(150L, holding.getQuantity());
        verify(portfolioHoldingRepository).save(holding);
    }

    @Test
    @DisplayName("Business Logic: Fill SELL order transitions to FILLED and decreases holding")
    void testFillSellOrder_DecreasesHolding() {
        TradeOrder order = new TradeOrder();
        order.setOrderId(11L);
        order.setTrader(testTrader);
        order.setStock(testStock);
        order.setQuantity(40L);
        order.setSide(OrderSide.SELL);
        order.setStatus(OrderStatus.PENDING);

        PortfolioHolding holding = new PortfolioHolding();
        holding.setHoldingId(1L);
        holding.setTrader(testTrader);
        holding.setStock(testStock);
        holding.setQuantity(100L);

        when(tradeOrderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL")).thenReturn(Optional.of(holding));
        when(tradeOrderRepository.save(any(TradeOrder.class))).thenReturn(order);

        OrderResponse response = orderService.fillOrder(11L);

        assertNotNull(response);
        assertEquals(OrderStatus.FILLED, response.getStatus());
        assertEquals(60L, holding.getQuantity());
        verify(portfolioHoldingRepository).save(holding);
    }

    @Test
    @DisplayName("Business Logic: Cancel PENDING order transitions to CANCELLED")
    void testCancelOrder_Success() {
        TradeOrder order = new TradeOrder();
        order.setOrderId(20L);
        order.setTrader(testTrader);
        order.setStock(testStock);
        order.setQuantity(10L);
        order.setSide(OrderSide.BUY);
        order.setStatus(OrderStatus.PENDING);

        when(tradeOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(tradeOrderRepository.save(any(TradeOrder.class))).thenReturn(order);

        OrderResponse response = orderService.cancelOrder(20L);

        assertNotNull(response);
        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        verify(tradeOrderRepository).save(order);
    }

    // ==========================================
    // Edge Cases Tests
    // ==========================================

    @Test
    @DisplayName("Edge Case: Fails when trader already has 3 pending orders (boundary limit)")
    void testPlaceOrder_Fails_WhenLimitExceeded() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .traderId("T001")
                .stock("AAPL")
                .sector("TECH")
                .quantity(50L)
                .side(OrderSide.BUY)
                .build();

        when(traderRepository.findByIdForUpdate("T001")).thenReturn(Optional.of(testTrader));
        when(tradeOrderRepository.countByTraderIdAndStatus("T001", OrderStatus.PENDING)).thenReturn(3L);

        assertThrows(OrderLimitExceededException.class, () -> orderService.placeOrder(request));
    }

    @Test
    @DisplayName("Edge Case: Fails when SELL quantity exceeds existing holding")
    void testPlaceOrder_Fails_WhenInsufficientSharesForSell() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .traderId("T001")
                .stock("AAPL")
                .sector("TECH")
                .quantity(100L)
                .side(OrderSide.SELL)
                .build();

        PortfolioHolding holding = new PortfolioHolding();
        holding.setQuantity(20L);

        when(traderRepository.findByIdForUpdate("T001")).thenReturn(Optional.of(testTrader));
        when(tradeOrderRepository.countByTraderIdAndStatus("T001", OrderStatus.PENDING)).thenReturn(0L);
        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL")).thenReturn(Optional.of(holding));

        assertThrows(InsufficientSharesException.class, () -> orderService.placeOrder(request));
    }

    @Test
    @DisplayName("Edge Case: Fails when SELL order placed with no holding record at all")
    void testPlaceOrder_Fails_WhenNoHoldingRecordForSell() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .traderId("T001")
                .stock("AAPL")
                .sector("TECH")
                .quantity(50L)
                .side(OrderSide.SELL)
                .build();

        when(traderRepository.findByIdForUpdate("T001")).thenReturn(Optional.of(testTrader));
        when(tradeOrderRepository.countByTraderIdAndStatus("T001", OrderStatus.PENDING)).thenReturn(0L);
        when(portfolioHoldingRepository.findByTraderIdAndStockSymbol("T001", "AAPL")).thenReturn(Optional.empty());

        assertThrows(InsufficientSharesException.class, () -> orderService.placeOrder(request));
    }

    @Test
    @DisplayName("Edge Case: Cannot fill an order that is already CANCELLED")
    void testFillOrder_Fails_WhenAlreadyCancelled() {
        TradeOrder order = new TradeOrder();
        order.setOrderId(12L);
        order.setStatus(OrderStatus.CANCELLED);

        when(tradeOrderRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> orderService.fillOrder(12L));
    }

    @Test
    @DisplayName("Edge Case: Cannot cancel an order that is already FILLED")
    void testCancelOrder_Fails_WhenAlreadyFilled() {
        TradeOrder order = new TradeOrder();
        order.setOrderId(21L);
        order.setStatus(OrderStatus.FILLED);

        when(tradeOrderRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(order));

        assertThrows(InvalidOrderStateException.class, () -> orderService.cancelOrder(21L));
    }

    @Test
    @DisplayName("Edge Case: Throws ResourceNotFoundException when filling non-existent order")
    void testFillOrder_Fails_WhenOrderNotFound() {
        when(tradeOrderRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.fillOrder(999L));
    }
}
