package com.example.model;

import org.babyfish.jimmer.sql.TypedTuple;

import java.math.BigDecimal;

@TypedTuple
public class StoreStatistics {

    private final long storeId;

    private final long bookCount;

    private final BigDecimal avgPrice;

    public StoreStatistics(long storeId, long bookCount, BigDecimal avgPrice) {
        this.storeId = storeId;
        this.bookCount = bookCount;
        this.avgPrice = avgPrice;
    }

    public long getStoreId() { return storeId; }

    public long getBookCount() { return bookCount; }

    public BigDecimal getAvgPrice() { return avgPrice; }

    @Override
    public String toString() {
        return "StoreStatistics(storeId=" + storeId + ", bookCount=" + bookCount + ", avgPrice=" + avgPrice + ")";
    }
}
