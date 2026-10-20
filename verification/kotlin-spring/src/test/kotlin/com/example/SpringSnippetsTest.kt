package com.example

import com.example.dto.BookInput
import com.example.dto.BookSpecification
import com.example.model.*
import com.example.repo.BookKRepository
import com.example.repo.BookRepository
import com.example.runtime.Hooks
import org.babyfish.jimmer.spring.repo.PageParam
import org.babyfish.jimmer.spring.model.SortUtils
import org.babyfish.jimmer.spring.repository.orderBy
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

@SpringBootTest
@AutoConfigureMockMvc
class SpringSnippetsTest {

    @Autowired lateinit var sqlClient: KSqlClient
    @Autowired lateinit var bookRepository: BookRepository
    @Autowired lateinit var bookKRepository: BookKRepository
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tx: TransactionTemplate

    private val nameFetcher = newFetcher(Book::class).by { name() }

    @Test
    fun starterAndHooks() {
        assertEquals(1, Hooks.CUSTOMIZED.get())
        assertEquals(1, Hooks.INITIALIZED.get())
        val saved = bookRepository.save(Book {
            name = "Spring"
            edition = 1
            price = BigDecimal.ONE
        }).modifiedEntity                                    // modifiedTime from the DraftInterceptor bean
        assertTrue(saved.id > 0)
        bookRepository.deleteById(saved.id, DeleteMode.PHYSICAL)
    }

    @Test
    fun classRepository() {
        val page = bookRepository.findBooks(PageParam.byIndex(0, 2), "graphql", nameFetcher)
        assertEquals(3, page.totalRowCount)
        assertEquals(2, page.rows.size)
        assertEquals(3, bookRepository.findAll(nameFetcher) { asc(Book::name); desc(Book::edition) }.size)
        assertEquals(1, bookRepository.findPage(PageParam.byNo(2, 2), nameFetcher) { asc(Book::name) }.rows.size)
        assertEquals(2, bookRepository.findByIds(listOf(1L, 2L), nameFetcher).size)
        val springPage = bookRepository.findBySpec(
            PageRequest.of(0, 10, SortUtils.toSort("name asc, edition desc")),
            BookSpecification(name = "graphql", minPrice = BigDecimal(51)),
            nameFetcher
        )
        assertEquals(2, springPage.totalElements)
        assertEquals("GraphQL in Action", springPage.content[0].name)
        assertEquals(2, bookRepository.avgPriceByStore().size)

        tx.executeWithoutResult { status ->
            val b = bookRepository.insert(Book { name = "Repo"; edition = 1; price = BigDecimal.TEN }).modifiedEntity
            bookRepository.update(Book { id = b.id; price = BigDecimal.ONE })
            bookRepository.save(Book { id = b.id; authors = listOf(Author { id = 1L }) }, SaveMode.UPDATE_ONLY, AssociatedSaveMode.MERGE)
            assertEquals(listOf(1L), bookRepository.findById(b.id, newFetcher(Book::class).by { authors() })!!.authors.map { it.id })
            assertEquals(2, bookRepository.raisePrice(1L, BigDecimal.ONE))
            status.setRollbackOnly()
        }
        bookRepository.save(BookInput(name = "FromInput", edition = 1, price = BigDecimal.ONE, storeId = 1L, authorIds = listOf(1L)))
            .modifiedEntity.let { bookRepository.deleteById(it.id, DeleteMode.PHYSICAL) }
    }

    @Test
    fun springDataRepository() {
        val books = bookKRepository.findByNameLikeIgnoreCaseAndPriceBetween("graphql", BigDecimal(50), null, nameFetcher)
        assertEquals(3, books.size)
        assertEquals(1, bookKRepository.findByNameLikeIgnoreCaseAndPriceBetween(null, null, BigDecimal(50)).size)
        assertEquals(1, bookKRepository.findByStoreName("MANNING", PageRequest.of(0, 10), nameFetcher).totalElements)
        assertEquals(2, bookKRepository.countByName("Learning GraphQL"))
        assertEquals(1, bookKRepository.findCheap(BigDecimal(50)).size)
        assertEquals(2, bookKRepository.findByNameOrderByEditionDesc("Learning GraphQL").size)
        assertThrows(NullPointerException::class.java) {           // `String?` alone is not dynamic
            bookKRepository.findByNameOrderByEditionDesc(null)
        }

        val names = sqlClient.createQuery(Book::class) {
            orderBy(SortUtils.toSort("name asc, edition desc"))
            select(table.name)
        }.execute()
        assertEquals("GraphQL in Action", names[0])
    }

    @Test
    fun web() {
        mvc.perform(get("/book/list"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("GraphQL in Action"))
            .andExpect(jsonPath("$[0].price").doesNotExist())
        mvc.perform(get("/book/1"))
            .andExpect(jsonPath("$.store.name").value("O'REILLY"))
            .andExpect(jsonPath("$.authors.length()").value(2))
        mvc.perform(get("/book/view/1")).andExpect(jsonPath("$.store.name").value("O'REILLY"))
        mvc.perform(post("/book/user").param("password", "123"))
            .andExpect(status().`is`(500))
            .andExpect(jsonPath("$.family").value("USER_INFO"))
            .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"))

        val yml = mvc.perform(asyncDispatch(mvc.perform(get("/openapi.yml")).andReturn()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(yml.contains("/book/list"), yml)
        assertTrue(yml.contains("SIMPLE_FETCHER"), yml)
        System.err.println("OPENAPI\n" + yml)

        val tsResult = mvc.perform(get("/ts.zip")).andReturn()
        val zip = (if (tsResult.request.isAsyncStarted) mvc.perform(asyncDispatch(tsResult)).andReturn() else tsResult)
            .response.contentAsByteArray
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) { entries += (zin.nextEntry ?: break).name }
        }
        System.err.println("TS ENTRIES $entries")
        assertTrue(entries.contains("Api.ts"), entries.toString())
        assertTrue(entries.any { it.startsWith("model/dto/") }, entries.toString())
    }
}
