package com.example.model

import org.babyfish.jimmer.sql.TypedTuple
import java.math.BigDecimal

@TypedTuple
data class StoreStatistics(
    val storeId: Long,
    val bookCount: Long,
    val avgPrice: BigDecimal?
)
