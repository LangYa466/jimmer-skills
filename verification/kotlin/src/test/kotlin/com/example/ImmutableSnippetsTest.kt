package com.example

import com.example.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.babyfish.jimmer.ImmutableObjects
import org.babyfish.jimmer.jackson.v2.ImmutableModuleV2
import org.babyfish.jimmer.kt.*
import org.babyfish.jimmer.sql.dialect.H2Dialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.kt.toKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor
import org.babyfish.jimmer.sql.runtime.SqlFormatter
import org.babyfish.jimmer.sql.transaction.Propagation
import org.h2.jdbcx.JdbcDataSource
import java.math.BigDecimal
import kotlin.test.*

class ImmutableSnippetsTest {

    @Test
    fun setup() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:kimm;DB_CLOSE_DELAY=-1") }
        val sqlClient: KSqlClient = newKSqlClient {
            setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
            setDialect(H2Dialect())
            setExecutor(Executor.log())
            setSqlFormatter(SqlFormatter.PRETTY)
            setDefaultBatchSize(128)
            setDefaultListBatchSize(16)
            setDefaultDissociateActionChecking(true)
        }
        val k2 = sqlClient.javaClient.toKSqlClient()
        assertNotNull(k2)
        val n = sqlClient.transaction { 1 + 1 }
        assertEquals(2, n)
        sqlClient.transaction(Propagation.REQUIRES_NEW) { }
    }

    @Test
    fun drafts() {
        val book = Book {
            name = "SQL in Action"
            edition = 1
            price = BigDecimal("49.9")
            store { id = 1L }
            authors().addBy { id = 1L }
            authors().addBy { id = 2L }
        }
        assertEquals(2, book.authors.size)
        val idOnly = makeIdOnly(Book::class, 10L)
        assertTrue(ImmutableObjects.isIdOnly(idOnly))
        assertTrue(ImmutableObjects.isIdOnly(makeIdOnly<Book>(10L)))
        val viaNew = new(Book::class).by { name = "X" }
        assertEquals("X", viaNew.name)

        val cheaper = book.copy { price = BigDecimal("39.9") }
        assertEquals(BigDecimal("49.9"), book.price)
        assertEquals(BigDecimal("39.9"), cheaper.price)
        assertEquals(2, Book(book) { edition = 2 }.edition)

        val tree = TreeNode {
            name = "Root"
            childNodes().addBy { name = "Child-1" }
        }
        val tree2 = tree.copy {
            childNodes()[0].name = "Child-1*"
            parent { name = "Daddy" }
        }
        assertEquals("Child-1*", tree2.childNodes[0].name)
        assertEquals("Daddy", tree2.parent!!.name)

        val b = Book {
            name = "GraphQL in Action"; edition = 3; price = BigDecimal("80")
            storeId = 2L
            authorIds = listOf(1L, 2L)
        }
        assertEquals(2L, b.store!!.id)
        val b2 = Book { name = "Y"; store = makeIdOnly(BookStore::class, 2L) }
        assertEquals(2L, b2.storeId)
        val noStore = Book { id = 8L; store = null }
        assertTrue(isLoaded(noStore, Book::store))
        assertNull(noStore.store)

        assertTrue(isLoaded(book, Book::name))
        assertFalse(isLoaded(book, Book::id))
        assertEquals("SQL in Action", get(book, Book::name))
        assertTrue(isLonely(toLonely(book)))
        val merged = merge(Book { name = "A" }, Book { edition = 2 })
        assertEquals("A", merged.name)
        assertEquals(2, merged.edition)
        assertEquals(book, deepClone(book))
        assertEquals(10L, toIdOnly(Book { id = 10L; name = "Z" }).id)
        assertNull(makeNullableIdOnly(Book::class, null))

        val edited = book.copy {
            set(this, BookDraft::price, BigDecimal.TEN)
            unload(this, BookDraft::authors)
            hide(this, BookDraft::store)
        }
        assertFalse(isLoaded(edited, Book::authors))
        assertFalse(edited.toString().contains("store"))
        assertEquals("name", Book::name.toImmutableProp().name)

        val parsed = fromString<Book>(book.toString())
        assertEquals(book.name, parsed.name)
        assertFailsWith<org.babyfish.jimmer.UnloadedException> { idOnly.name }
    }

    @Test
    fun json() {
        val mapper = ObjectMapper()
            .registerModule(ImmutableModuleV2())
            .registerModule(JavaTimeModule())
            .registerKotlinModule()
        val book = Book { name = "N"; edition = 1; price = BigDecimal.ONE }
        val json = mapper.writeValueAsString(book)
        val back = mapper.readValue(json, Book::class.java)
        println("PROBE ${back.price.javaClass} ${back.price.scale()} ${book.price.scale()} same=${book == book.copy { }} eqName=${book.name == back.name} ${back.edition == book.edition} ${back.price == book.price}")
        assertEquals(book.name, back.name)
        assertFalse(json.contains("store"))
    }
}
