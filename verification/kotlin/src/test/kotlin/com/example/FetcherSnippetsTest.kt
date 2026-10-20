package com.example

import com.example.dto.BookView
import com.example.mapping.*
import com.example.model.*
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.fetcher.IdOnlyFetchType
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import kotlin.test.*

val BOOK_DETAIL: Fetcher<Book> = newFetcher(Book::class).by {
    allScalarFields()
    tenant(false)
    store { name() }
    authors {
        firstName()
        lastName()
    }
}

class FetcherSnippetsTest {

    private val sqlClient = TestSupport.client(
        "kf",
        "create table client(id bigint primary key, client_type varchar(10) not null, name varchar(20) not null, tax_code varchar(20), first_name varchar(20), last_name varchar(20))",
        "insert into client values(1, 'ORG', 'Acme', 'T1', null, null), (2, 'Person', 'Bob', null, 'Bob', 'Lee')"
    )

    @Test
    fun dsl() {
        val books = sqlClient.createQuery(Book::class) { where(table.id eq 1L); select(table.fetch(BOOK_DETAIL)) }.execute()
        assertEquals(2, books[0].authors.size)
        assertFalse(isLoaded(books[0], Book::tenant))
        val inline = sqlClient.createQuery(Book::class) {
            where(table.id eq 1L)
            select(table.fetchBy { name(); store(); authorIds() })
        }.fetchOne()
        assertEquals(1L, inline.store!!.id)
        val extended = newFetcher(Book::class).by(BOOK_DETAIL) { price() }
        assertNotNull(sqlClient.findById(extended, 1L)!!.price)
        assertNotNull(sqlClient.findById(newFetcher(Book::class).by { store(IdOnlyFetchType.RAW) }, 1L)!!.store)
        val all = sqlClient.createQuery(Book::class) { where(table.id eq 1L); select(table.fetch(null)) }.fetchOne()
        assertTrue(isLoaded(all, Book::store))

        val cfg = newFetcher(Book::class).by {
            store(ReferenceFetchType.JOIN_ALWAYS) { name() }
            authors({
                batch(32)
                limit(10)
                filter {
                    where(table.firstName ilike "a")
                    orderBy(table.lastName)
                }
            }) {
                firstName()
                lastName()
            }
        }
        val b = sqlClient.findById(cfg, 1L)!!
        assertEquals(1, b.authors.size)                 // only Alex matches "a"... and Eve? (ilike %a% → Alex)
    }

    @Test
    fun recursionViewsInheritance() {
        val roots = sqlClient.createQuery(TreeNode::class) {
            where(table.parentId.isNull())
            select(table.fetchBy { name(); `childNodes*`() })
        }.execute()
        assertEquals(2, roots[0].childNodes.size)
        assertNotNull(sqlClient.findById(newFetcher(TreeNode::class).by { name(); `childNodes*` { depth(2) } }, 1L))
        val stopped = sqlClient.findById(newFetcher(TreeNode::class).by { name(); `childNodes*` { recursive { entity.name != "Clothing" } } }, 1L)!!
        val clothing = stopped.childNodes.first { it.name == "Clothing" }
        assertFalse(isLoaded(clothing, TreeNode::childNodes))
        val leaf = sqlClient.findById(newFetcher(TreeNode::class).by { name(); `parent*`() }, 8L)!!
        assertEquals("Home", leaf.parent!!.parent!!.parent!!.name)

        assertEquals(1L, sqlClient.findById(newFetcher(Book::class).by { allScalarFields(); storeId(); authorIds() }, 1L)!!.storeId)
        val author = sqlClient.findById(newFetcher(Author::class).by { fullName(); sqlFullName() }, 1L)!!
        assertEquals("Eve Procello", author.fullName)
        assertTrue(sqlClient.findById(newFetcher(BookStore::class).by { name(); avgPrice() }, 1L)!!.avgPrice > java.math.BigDecimal.ZERO)
        val clients = sqlClient.findAll(newFetcher(Client::class).by {
            name()
            forType(Organization::class) { taxCode() }
            forType(Person::class) { firstName(); lastName() }
        })
        assertEquals("Lee", (clients.first { it is Person } as Person).lastName)
    }

    @Test
    fun placesAndDtos() {
        assertNotNull(sqlClient.findOneById(BOOK_DETAIL, 1L))
        assertEquals(2, sqlClient.findByIds(BOOK_DETAIL, listOf(1L, 2L)).size)
        assertEquals(2, sqlClient.findMapByIds(BOOK_DETAIL, listOf(1L, 2L)).size)
        assertTrue(sqlClient.findAll(BOOK_DETAIL) { where(table.edition eq 1); orderBy(table.name) }.isNotEmpty())
        assertTrue(sqlClient.entities.findAll(BOOK_DETAIL) { asc(Book::name) }.isNotEmpty())
        val saved = sqlClient.saveCommand(Book { id = 1L; price = java.math.BigDecimal("60") }).execute(BOOK_DETAIL).modifiedEntity
        assertEquals(2, saved.authors.size)

        val views: List<BookView> = sqlClient.createQuery(Book::class) {
            where(table.edition eq 1)
            select(table.fetch(BookView::class))
        }.execute()
        assertTrue(views.isNotEmpty())
        val one: BookView? = sqlClient.findById(BookView::class, 1L)
        assertNotNull(one)
        val all: List<BookView> = sqlClient.findAll(BookView::class) { where(table.edition eq 1) }
        assertTrue(all.isNotEmpty())
        val f: Fetcher<Book> = BookView.METADATA.fetcher
        val entity = sqlClient.findById(f, 1L)!!
        val view = BookView(entity)
        assertEquals(entity.name, view.toEntity().name)
    }
}
