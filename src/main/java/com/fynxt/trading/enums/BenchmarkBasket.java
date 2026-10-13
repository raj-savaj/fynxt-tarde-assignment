package com.fynxt.trading.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public enum BenchmarkBasket {
    TECH_HEAVY(Set.of("AAPL", "MSFT", "GOOGL", "TSLA", "NVDA")),
    FINANCE_HEAVY(Set.of("JPM", "GS", "BAC", "MS", "WFC")),
    BALANCED(Set.of("AAPL", "JPM", "XOM", "JNJ", "TSLA"));

    private final Set<String> stocks;

    BenchmarkBasket(Set<String> stocks) {
        this.stocks = Collections.unmodifiableSet(new LinkedHashSet<>(stocks));
    }

    public Set<String> getStocks() {
        return stocks;
    }
}
