package com.example.mapping
import org.babyfish.jimmer.Formula
import org.babyfish.jimmer.jackson.Converter
import org.babyfish.jimmer.jackson.JsonConverter
import org.babyfish.jimmer.jackson.LongToStringConverter
import org.babyfish.jimmer.sql.*
import org.babyfish.jimmer.sql.meta.UUIDIdGenerator
import org.babyfish.jimmer.sql.meta.UserIdGenerator
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Entity interface Shop {
    @Id val id: Long
    val name: String
    @ManyToMany
    @JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
        filter = JoinTable.JoinTableFilter(columnName = "TYPE", values = ["VIP"]))
    val vipCustomers: List<Customer>
    @ManyToMany
    @JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID", readonly = true)
    val customers: List<Customer>
}
