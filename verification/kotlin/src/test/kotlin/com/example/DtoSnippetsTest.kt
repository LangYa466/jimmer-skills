package com.example

import com.example.dto.*
import com.example.dto.adv.*
import com.example.dto.client.ClientView
import com.example.mapping.Client
import com.example.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.babyfish.jimmer.jackson.v2.ImmutableModuleV2
import org.babyfish.jimmer.kt.isLoaded
import java.math.BigDecimal
import kotlin.test.*

class DtoSnippetsTest {

    private val sqlClient = TestSupport.client(
        "kd", tenantFilter = true, extraSql = arrayOf(
        "create table client(id bigint primary key, client_type varchar(10) not null, name varchar(20) not null, tax_code varchar(20), first_name varchar(20), last_name varchar(20))",
        "insert into client values(1, 'ORG', 'Acme', 'T1', null, null), (2, 'Person', 'Bob', null, 'Bob', 'Lee')"
    ))

    private val mapper = ObjectMapper().registerModule(ImmutableModuleV2()).registerKotlinModule()

    @Test
    fun nullModes() {
        val e1 = BookPatch(id = 1L, price = BigDecimal.TEN).toEntity()
        assertFalse(isLoaded(e1, Book::name))
        assertFalse(isLoaded(e1, Book::store))
        assertTrue(isLoaded(e1, Book::price))
        val e2 = BookPatch(id = 1L, storeId = null, isStoreIdLoaded = true).toEntity()
        assertTrue(isLoaded(e2, Book::store))                   // explicit NULL for a nullable entity prop
        assertNull(e2.store)
        val e3 = BookPatch(id = 1L, name = null, isNameLoaded = true).toEntity()
        assertFalse(isLoaded(e3, Book::name))                   // entity prop is non-null: null = unloaded
        val fromJson = mapper.readValue("""{"id":1,"storeId":null}""", BookPatch::class.java)
        assertTrue(fromJson.isStoreIdLoaded)
        assertFalse(fromJson.isPriceLoaded)
        assertFails { mapper.readValue("""{"id":1}""", BookFixedPatch::class.java) }      // fixed storeId missing
        assertNotNull(mapper.readValue("""{"id":1,"storeId":null}""", BookFixedPatch::class.java))
        val fuzzy = mapper.readValue("""{"id":1,"storeId":null}""", BookFuzzyPatch::class.java).toEntity()
        assertFalse(isLoaded(fuzzy, Book::store))
        val upd = UpdateBookInput(id = 1L, name = "N").toEntity()
        assertFalse(isLoaded(upd, Book::price))
    }

    @Test
    fun outputsSpecsTypes() {
        val custom = BookWithCustom(id = 1L, remark = "r", tags = listOf("a"))
        assertEquals(0, custom.rank)
        val filtered = sqlClient.findById(BookFilterClassView::class, 1L)!!
        assertEquals(listOf("Alex"), filtered.authors.map { it.firstName })
        val gender = sqlClient.findById(AuthorGenderView::class, 1L)!!
        assertEquals(101, gender.gender)
        val tree = sqlClient.findById(RecursiveTreeView::class, 1L)!!
        assertTrue(tree.childNodes!!.isNotEmpty())

        val spec = BookSpecification(name = "graphql", minPrice = BigDecimal(40), authorName = "eve")
        assertEquals(2, sqlClient.createQuery(Book::class) { where(spec); select(table) }.execute().size)
        val adv = BookAdvancedSpecification(editions = listOf(1, 2), storeIds = listOf(1L), authorName = "a")
        assertTrue(sqlClient.createQuery(Book::class) { where(adv); select(table.id) }.execute().isNotEmpty())
        assertEquals(0, sqlClient.createQuery(Book::class) { where(BookAdvancedSpecification(noStore = true)); select(table.id) }.execute().size)

        val clients = sqlClient.findAll(ClientView::class)
        assertTrue(clients.any { it is ClientView.Organization && it.taxCode == "T1" })
        assertTrue(clients.any { it is ClientView.Person && it.lastName == "Lee" })
    }

    @Test
    fun usage() {
        val input = BookInput(name = "DTO Book", edition = 1, price = BigDecimal("12.5"), storeId = 2L, authorIds = listOf(1L, 2L))
        assertTrue(sqlClient.save(input).modifiedEntity.id > 0)
        val entity: Book = input.toEntity { tenant = "a" }
        assertEquals("a", entity.tenant)
        val nested = CompositeBookStoreInput(
            name = "COMPOSITE",
            books = listOf(CompositeBookStoreInput.TargetOf_books(name = "B", edition = 1, price = BigDecimal.ONE, authorIds = listOf(3L)))
        )
        sqlClient.save(nested)
        val db = sqlClient.findById(BookView.METADATA.fetcher, 1L)!!
        val view = BookView(db)
        assertEquals(db.name, view.name)
        assertNotNull(sqlClient.findById(BookView::class, 1L))
        assertEquals("B", input.copy(name = "B").name)
    }
}
