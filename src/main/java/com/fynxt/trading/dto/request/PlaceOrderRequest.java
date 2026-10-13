package com.fynxt.trading.dto.request;

import com.fynxt.trading.enums.OrderSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceOrderRequest {

    @NotBlank(message = "traderId must not be blank")
    private String traderId;

    @NotBlank(message = "stock must not be blank")
    private String stock;

    @NotBlank(message = "sector must not be blank")
    private String sector;

    @NotNull(message = "quantity must not be null")
    @Positive(message = "quantity must be greater than zero")
    private Long quantity;

    @NotNull(message = "side must not be null")
    private OrderSide side;
}
