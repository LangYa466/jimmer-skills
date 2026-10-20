package com.example.runtime

import com.example.model.*
import org.babyfish.jimmer.kt.toImmutableProp
import org.babyfish.jimmer.sql.event.AssociationEvent
import org.babyfish.jimmer.sql.event.EntityEvent
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.KTransientResolver
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.event.getUnchangedRef
import org.babyfish.jimmer.sql.kt.event.isChanged
import java.math.BigDecimal

class BookStoreAvgPriceResolver(
    private val sqlClient: KSqlClient
) : KTransientResolver<Long, BigDecimal> {

    override fun resolve(ids: Collection<Long>): Map<Long, BigDecimal> =
        sqlClient
            .createQuery(Book::class) {
                where(table.storeId valueIn ids)
                groupBy(table.storeId)
                select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
            }
            .execute()
            .associate { it._1 to it._2 }

    override fun getDefaultValue(): BigDecimal = BigDecimal.ZERO

    // a book moved between stores
    override fun getAffectedSourceIds(e: AssociationEvent): Collection<*>? =
        if (sqlClient.caches.isAffectedBy(e) && e.immutableProp == BookStore::books.toImmutableProp()) {
            listOf(e.sourceId)
        } else {
            null
        }

    // a book price changed while its store stayed the same
    override fun getAffectedSourceIds(e: EntityEvent<*>): Collection<*>? {
        if (sqlClient.caches.isAffectedBy(e) && !e.isEvict && e.immutableType.javaClass == Book::class.java) {
            val storeId = e.getUnchangedRef(Book::store)?.value?.id
            if (storeId !== null && e.isChanged(Book::price)) {
                return listOf(storeId)
            }
        }
        return null
    }
}
