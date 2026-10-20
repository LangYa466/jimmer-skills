package com.example.model

import com.example.model.common.BaseEntity
import com.example.runtime.BookStoreAvgPriceResolver
import org.babyfish.jimmer.sql.*
import java.math.BigDecimal

@Entity
@KeyUniqueConstraint
interface BookStore : BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val name: String

    val website: String?

    @Version
    val version: Int

    @OneToMany(
        mappedBy = "store",
        orderedProps = [OrderedProp("name"), OrderedProp(value = "edition", desc = true)]
    )
    val books: List<Book>

    @Transient(BookStoreAvgPriceResolver::class)
    val avgPrice: BigDecimal
}
