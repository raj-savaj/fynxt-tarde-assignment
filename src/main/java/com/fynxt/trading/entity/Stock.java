package com.fynxt.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "stocks")
@Getter
@Setter
public class Stock {

    @Id
    @Column(name = "stock_symbol")
    private String stockSymbol;

    @Column(name = "stock_name")
    private String stockName;

    @Column(name = "sector")
    private String sector;
}
