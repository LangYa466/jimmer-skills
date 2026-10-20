package com.example

import com.example.dto.BookSpecification
import com.example.dto.BookView
import com.example.model.*
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.table.makeOrders
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val SKILL_BOOK_DETAIL = newFetcher(Book::class).by {
    allScalarFields()
    store { name() }
    authors({ batch(32) }) { firstName(); lastName() }
}

class SkillSnippetsTest {

    @Test
    fun quickStartAndTemplates() {
        val sqlClient = TestSupport.client("kskill")
        val keyword: String? = "graphql"
        val name = "SQL in Action"
        val storeName: String? = null
        val books: List<Book> = sqlClient.createQuery(Book::class) {
            where(table.name `ilike?` keyword)
            where(table.store.name `eq?` storeName)
            orderBy(table.name.asc(), table.edition.desc())
            select(table.fetchBy {
                allScalarFields()
                store { name() }
            })
        }.execute()
        assertEquals(5, books.size)

        val id = sqlClient.save(Book {
            this.name = name                              // a local `name` shadows the draft property
            edition = 1
            price = BigDecimal("49.9")
            storeId = 2L
        }).modifiedEntity.id
        assertTrue(id > 0)

        val minPrice: BigDecimal? = BigDecimal(50)
        val maxPrice: BigDecimal? = null
        val author: String? = "a"
        val page = sqlClient.createQuery(Book::class) {
            where(table.price.`between?`(minPrice, maxPrice))
            where += table.authors {
                or(firstName `ilike?` author, lastName `ilike?` author)
            }
            orderBy(table.makeOrders("store.name asc, edition desc"))
            select(table.fetch(SKILL_BOOK_DETAIL))
        }.fetchPage(0, 2)
        assertTrue(page.totalRowCount > 0)

        val spec = BookSpecification(name = "graphql")
        val views: List<BookView> = sqlClient.createQuery(Book::class) {
            where(spec)
            select(table.fetch(BookView::class))
        }.execute()
        assertTrue(views.isNotEmpty())

        sqlClient.save(Book { this.id = id; price = BigDecimal.TEN }, SaveMode.UPDATE_ONLY)
        sqlClient.deleteById(Book::class, id)
        sqlClient.deleteById(Book::class, id, DeleteMode.PHYSICAL)

        val distinctBooks = sqlClient.createQuery(Book::class) {
            where(table.asTableEx().authors.firstName eq "Alex")
            select(table.id)
        }.distinct().execute()
        assertTrue(distinctBooks.isNotEmpty())
    }
}
