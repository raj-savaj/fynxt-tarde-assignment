package com.fynxt.trading.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SectorOverlapResponse {
    private List<BasketOverlapItem> overlaps;
    private String dominantBasket;
    private String riskFlag;
}
