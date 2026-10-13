package com.fynxt.trading.dto.response;

import com.fynxt.trading.enums.OrderSide;
import com.fynxt.trading.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {
    private Long orderId;
    private String traderId;
    private String stock;
    private String sector;
    private Long quantity;
    private OrderSide side;
    private OrderStatus status;
    private Instant createdAt;
}
