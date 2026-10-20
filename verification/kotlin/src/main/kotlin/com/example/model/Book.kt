package com.example.model

import com.example.model.common.BaseEntity
import com.example.model.common.TenantAware
import org.babyfish.jimmer.sql.*
import java.math.BigDecimal

@Entity
@KeyUniqueConstraint
interface Book : BaseEntity, TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val name: String

    @Key
    val edition: Int

    val price: BigDecimal

    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    val store: BookStore?

    @ManyToMany(orderedProps = [OrderedProp("firstName"), OrderedProp("lastName")])
    @JoinTable(
        name = "BOOK_AUTHOR_MAPPING",
        joinColumnName = "BOOK_ID",
        inverseJoinColumnName = "AUTHOR_ID"
    )
    val authors: List<Author>

    @IdView
    val storeId: Long?

    @IdView("authors")
    val authorIds: List<Long>
}
