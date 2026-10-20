package com.example.repo

import com.example.dto.BookSpecification
import com.example.model.*
import org.babyfish.jimmer.Page
import org.babyfish.jimmer.spring.repo.PageParam
import org.babyfish.jimmer.spring.repo.support.AbstractKotlinRepository
import org.babyfish.jimmer.spring.repository.fetchSpringPage
import org.babyfish.jimmer.spring.repository.orderBy
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class BookRepository(sql: KSqlClient) : AbstractKotlinRepository<Book, Long>(sql) {

    fun findBooks(page: PageParam, name: String?, fetcher: Fetcher<Book>?): Page<Book> =
        createQuery {                                       // protected helper: sql.createQuery(Book::class)
            where(table.name `ilike?` name)
            orderBy(table.name.asc(), table.edition.desc())
            select(table.fetch(fetcher))
        }.fetchPage(page.index, page.size)

    fun findBySpec(pageable: Pageable, spec: BookSpecification, fetcher: Fetcher<Book>?) =
        createQuery {
            where(spec)
            orderBy(pageable.sort)                          // org.babyfish.jimmer.spring.repository.orderBy
            select(table.fetch(fetcher))
        }.fetchSpringPage(pageable)                         // org.springframework.data.domain.Page

    fun avgPriceByStore(): Map<Long, BigDecimal> =
        executeQuery {
            where(table.storeId.isNotNull())
            groupBy(table.storeId)
            select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
        }.associate { it._1 to it._2 }

    fun raisePrice(storeId: Long, delta: BigDecimal): Int =
        executeUpdate {
            set(table.price, table.price + delta)
            where(table.storeId eq storeId)
        }
}
