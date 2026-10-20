package com.example.repo

import com.example.model.*
import org.babyfish.jimmer.spring.repository.KRepository
import org.babyfish.jimmer.spring.repository.DynamicParam
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.ast.expression.le
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.math.BigDecimal

interface BookKRepository : KRepository<Book, Long> {

    fun findByNameLikeIgnoreCaseAndPriceBetween(
        @DynamicParam name: String?,
        @DynamicParam minPrice: BigDecimal?,
        @DynamicParam maxPrice: BigDecimal?,
        fetcher: Fetcher<Book>? = null
    ): List<Book>

    fun findByStoreName(@DynamicParam storeName: String?, pageable: Pageable, fetcher: Fetcher<Book>? = null): Page<Book>

    fun findByNameOrderByEditionDesc(name: String?): List<Book>

    fun countByName(name: String): Long

    fun findCheap(max: BigDecimal): List<Book> =
        sql.createQuery(Book::class) {
            where(table.price le max)
            select(table)
        }.execute()
}
