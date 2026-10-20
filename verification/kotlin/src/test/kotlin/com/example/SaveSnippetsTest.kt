package com.example

import com.example.dto.BookInput
import com.example.dto.BookView
import com.example.model.*
import org.babyfish.jimmer.sql.DissociateAction
import org.babyfish.jimmer.sql.TargetTransferMode
import org.babyfish.jimmer.sql.ast.mutation.*
import org.babyfish.jimmer.sql.exception.SaveException
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.mutation.addInsertableProp
import org.babyfish.jimmer.sql.kt.ast.mutation.addUpdatableProp
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator
import java.math.BigDecimal
import kotlin.test.*

class SaveSnippetsTest {

    private val sqlClient = TestSupport.client("ks")

    private fun authorIds(bookId: Long) = sqlClient.findById(newFetcher(Book::class).by { authorIds() }, bookId)!!.authorIds.toSet()

    @Test
    fun defaults() {
        // save(entity, mode) defaults to MERGE: existing links kept
        sqlClient.save(Book { id = 1L; authorIds = listOf(3L) }, SaveMode.UPDATE_ONLY)
        assertEquals(setOf(1L, 2L, 3L), authorIds(1L))
        // saveCommand(entity, mode) defaults to REPLACE
        sqlClient.saveCommand(Book { id = 1L; authorIds = listOf(3L) }, SaveMode.UPDATE_ONLY).execute()
        assertEquals(setOf(3L), authorIds(1L))
        // save(entity) defaults to REPLACE
        sqlClient.save(Book { id = 1L; authorIds = listOf(1L, 2L) })
        assertEquals(setOf(1L, 2L), authorIds(1L))

        sqlClient.save(Book { id = 1L; store = null }, SaveMode.UPDATE_ONLY)
        assertNull(sqlClient.findById(newFetcher(Book::class).by { storeId() }, 1L)!!.storeId)
        val book = Book { name = "New"; edition = 1; price = BigDecimal.TEN }
        sqlClient.save(book, SaveMode.INSERT_ONLY, AssociatedSaveMode.APPEND)
        sqlClient.save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.UPDATE)
        assertFalse(sqlClient.save(book, SaveMode.INSERT_IF_ABSENT, AssociatedSaveMode.APPEND_IF_ABSENT).isAccepted)
        sqlClient.save(book) {
            setMode(SaveMode.UPSERT)
            setAssociatedModeAll(AssociatedSaveMode.MERGE)
            setAssociatedMode(Book::authors, AssociatedSaveMode.APPEND)
        }
        sqlClient.saveCommand(book) { setMode(SaveMode.UPDATE_ONLY) }.execute()

        val r = sqlClient.save(Book {
            name = "Book"; edition = 1; price = BigDecimal.TEN
            authors().addBy { firstName = "Alex"; lastName = "Banks" }
        }) {
            setKeyOnlyAsReference(Book::authors)
        }
        assertEquals(1, r.affectedRowCount(Book::authors))
        assertTrue(r.affectedRowCountMap.isNotEmpty())
        assertTrue(r.isModified)
        assertNotNull(r.originalEntity)
        sqlClient.save(Book { name = "Book"; edition = 1; price = BigDecimal.ONE }) { setKeyProps(Book::name, Book::edition) }
        val matched = sqlClient.save(Book { id = 999L; name = "Book"; edition = 1; price = BigDecimal.ONE }) { matchByKey() }.modifiedEntity
        assertNotEquals(999L, matched.id)
        assertFailsWith<SaveException.IllegalTargetId> {
            sqlClient.save(Book { id = 1L; authors().addBy { id = 12345L } }) { setAutoIdOnlyTargetChecking(Book::authors) }
        }
    }

    @Test
    fun dissociationResultsUpdateControl() {
        val steal = BookStore { id = 1L; books().addBy { id = 5L } }
        assertFailsWith<SaveException.TargetIsNotTransferable> {
            sqlClient.save(steal, SaveMode.UPSERT, AssociatedSaveMode.MERGE)
        }
        sqlClient.save(BookStore { id = 2L; books().addBy { id = 6L } }) {
            setDissociateAction(Book::store, DissociateAction.DELETE)
            setTargetTransferMode(BookStore::books, TargetTransferMode.ALLOWED)
        }
        assertEquals(1, sqlClient.findById(newFetcher(BookStore::class).by { books() }, 2L)!!.books.size)

        val items = sqlClient.saveEntities(listOf(Book { name = "A"; edition = 1; price = BigDecimal.ONE })).items
        assertTrue(items[0].isAccepted)
        val b = Book { id = 1L; price = BigDecimal("99") }
        val saved = sqlClient.saveCommand(b).execute(newFetcher(Book::class).by { allScalarFields(); store { name() } }).modifiedEntity
        assertEquals("O'REILLY", saved.store!!.name)
        val view: BookView = sqlClient.saveCommand(b).execute(BookView::class).modifiedView
        assertEquals(2, view.authors.size)
        sqlClient.save(b) { setSaveResultReadsAllProperties(true); setSaveReturningEnabled(false) }

        val book = Book { name = "Learning GraphQL"; edition = 1; price = BigDecimal("1.00"); storeId = 2L }
        sqlClient.save(book) { setUpsertMask(Book::price) }
        assertEquals(1L, sqlClient.findById(newFetcher(Book::class).by { storeId() }, 1L)!!.storeId)
        sqlClient.save(book) {
            setUpsertMask(
                UpsertMask.of(Book::class.java)
                    .addInsertableProp(Book::name).addInsertableProp(Book::edition).addInsertableProp(Book::price)
                    .addUpdatableProp(Book::price)
            )
        }
        assertTrue(sqlClient.save(book) { forbidUpdate() }.isAccepted)
        // equal prices: column-left form correctly rejects even on the interceptor pre-query path
        assertFalse(sqlClient.save(book) { setUpdateWhere(Book::class) { table.price lt newNonNull(Book::price) } }.isAccepted)
        // Kotlin's newNonNull is rendered correctly on the left too (the Java API is affected, see Java skill)
        assertFalse(sqlClient.save(book) { setUpdateWhere(Book::class) { newNonNull(Book::price) gt table.price } }.isAccepted)

        sqlClient.save(Book { id = 1L; price = BigDecimal("5") }, SaveMode.UPDATE_ONLY) {
            set(Book::price) { target.price + newNonNull(Book::price) }
        }
        assertEquals(0, BigDecimal("6.00").compareTo(sqlClient.findById(newFetcher(Book::class).by { price() }, 1L)!!.price))
        val vr = sqlClient.save(BookStore { id = 1L; name = "O'REILLY"; version = 7 }) {
            setVersionMode(VersionMode.ASSIGNMENT)
            setUpdateWhere(BookStore::class) { table.version lt newNonNull(BookStore::version) }
        }
        assertTrue(vr.isAccepted)
    }

    @Test
    fun lockingInputsErrors() {
        assertFailsWith<SaveException.OptimisticLockError> {
            sqlClient.save(BookStore { id = 1L; website = "w"; version = 3 }, SaveMode.UPDATE_ONLY)
        }
        val stores = listOf(BookStore { id = 1L; website = "https://www.oreilly.com" }, BookStore { id = 2L; website = "https://www.manning.com" })
        sqlClient.saveEntities(stores, SaveMode.UPDATE_ONLY) {
            setOptimisticLock(BookStore::class) {
                sql(Boolean::class, "coalesce(length(%e), 0) <= length(%e)") {
                    expression(table.website)
                    expression(newNullable(BookStore::website))
                }
            }
        }
        sqlClient.save(BookStore { id = 1L; name = "O'REILLY"; website = "x" }) {
            setOptimisticLock(BookStore::class, UnloadedVersionBehavior.INCREASE) { table.name eq newNonNull(BookStore::name) }
        }
        assertEquals(1, sqlClient.findById(newFetcher(BookStore::class).by { version() }, 1L)!!.version)
        sqlClient.transaction {
            sqlClient.saveEntities(listOf(Book { name = "L1"; edition = 1; price = BigDecimal.ONE })) { setPessimisticLock(Book::class) }
            sqlClient.save(BookStore { id = 1L; name = "O'REILLY" }) { setPessimisticLockAll() }
        }

        val input = BookInput(name = "I1", edition = 1, price = BigDecimal.ONE, storeId = null, authorIds = emptyList())
        sqlClient.save(input)
        sqlClient.saveInputs(listOf(input, BookInput(name = "I2", edition = 1, price = BigDecimal.ONE, storeId = null, authorIds = emptyList())))
        sqlClient.save(input, SaveMode.UPDATE_ONLY, AssociatedSaveMode.REPLACE)

        val ex = assertFailsWith<IllegalArgumentException> {
            sqlClient.save(Book { name = "Learning GraphQL"; edition = 1; price = BigDecimal.ONE }, SaveMode.INSERT_ONLY) {
                addExceptionTranslator(object : ExceptionTranslator<SaveException.NotUnique> {
                    override fun translate(exception: SaveException.NotUnique, args: ExceptionTranslator.Args?): Exception? =
                        if (exception.isMatched(BookProps.NAME, BookProps.EDITION)) {
                            IllegalArgumentException("Book ${exception.getValue(BookProps.NAME)} edition ${exception.getValue(BookProps.EDITION)} already exists")
                        } else {
                            null
                        }
                })
            }
        }
        assertEquals("Book Learning GraphQL edition 1 already exists", ex.message)
        val wild = assertFailsWith<SaveException.NeitherIdNorKey> { sqlClient.save(Book { price = BigDecimal.ONE }) }
        assertNotNull(wild.path)
        assertNotNull(wild.saveErrorCode)
    }
}
