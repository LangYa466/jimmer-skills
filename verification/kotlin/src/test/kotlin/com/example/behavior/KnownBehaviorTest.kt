package com.example.behavior

import com.example.TestSupport
import com.example.model.*
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.exception.SaveException
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Surprising behaviors of Jimmer 0.12.4 that the skills document.
 * If one of these tests starts failing after a Jimmer upgrade, update the skills.
 */
class KnownBehaviorTest {

    @Test
    fun constantOnTheLeftUsesNegatedOperator() {
        val sqlClient = TestSupport.client("kkb1")       // book 1 costs 50.00
        val fifty = BigDecimal("50.00")
        val gt = sqlClient.createQuery(Book::class) {
            where(table.id eq 1L)
            where(value(fifty) gt table.price)           // rendered as PRICE <= 50
            select(table.id)
        }.execute()
        assertFalse(gt.isEmpty())                        // 50 > 50 should be false
        val columnLeft = sqlClient.createQuery(Book::class) {
            where(table.id eq 1L)
            where(table.price lt fifty)
            select(table.id)
        }.execute()
        assertTrue(columnLeft.isEmpty())
    }

    @Test
    fun updateWhereWithNewNonNullIsNotNegated() {
        val sqlClient = TestSupport.client("kkb2")       // has draft interceptors → pre-query path
        val r = sqlClient.save(Book { id = 1L; price = BigDecimal("50.00") }, SaveMode.UPDATE_ONLY) {
            setUpdateWhere(Book::class) { newNonNull(Book::price) gt table.price }
        }
        assertFalse(r.isAccepted)
    }

    @Test
    fun valueInIgnoresNullButNotEmpty() {
        val sqlClient = TestSupport.client("kkb3")
        val none: List<Long>? = null
        assertEquals(6, sqlClient.createQuery(Book::class) { where(table.id `valueIn?` none); select(table.id) }.execute().size)
        assertEquals(0, sqlClient.createQuery(Book::class) { where(table.id `valueIn?` emptyList()); select(table.id) }.execute().size)
    }

    @Test
    fun lambdaExceptionTranslatorIsRejected() {
        val sqlClient = TestSupport.client("kkb4")
        val ex = assertFailsWith<IllegalArgumentException> {
            sqlClient.save(
                Book { name = "Learning GraphQL"; edition = 1; price = BigDecimal.ONE; tenant = "a" },
                SaveMode.INSERT_ONLY
            ) {
                addExceptionTranslator(ExceptionTranslator<SaveException.NotUnique> { _, _ -> IllegalStateException("dup") })
            }
        }
        assertTrue(ex.message!!.contains("Illegal exception translator type"), ex.message)
    }
}
