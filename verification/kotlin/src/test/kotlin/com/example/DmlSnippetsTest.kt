package com.example

import com.example.mapping.*
import com.example.model.*
import org.babyfish.jimmer.sql.DissociateAction
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.ast.tuple.Tuple2
import org.babyfish.jimmer.sql.exception.SaveException
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.mutation.KDeleteResult
import org.babyfish.jimmer.sql.kt.ast.query.baseTableSymbol
import org.babyfish.jimmer.sql.kt.ast.table.sourceId
import org.babyfish.jimmer.sql.kt.ast.table.targetId
import org.babyfish.jimmer.sql.kt.createInsert
import org.babyfish.jimmer.sql.kt.deleteById
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior
import kotlin.test.*

class DmlSnippetsTest {

    private val sqlClient = TestSupport.client("kdml", "create table course(id bigint primary key, name varchar(20) not null)")

    @Test
    fun deletes() {
        val r1: KDeleteResult = sqlClient.deleteById(Book::class, 1L)
        assertEquals(2, r1.affectedRowCount(Book::authors))
        sqlClient.deleteByIds(Book::class, listOf(2L, 3L))
        sqlClient.deleteById(Author::class, 3L, DeleteMode.PHYSICAL)
        sqlClient.deleteById<Book>(4L)
        val r = sqlClient.deleteById(BookStore::class, 1L) {
            setDissociateAction(Book::store, DissociateAction.SET_NULL)
            setMode(DeleteMode.PHYSICAL)
        }
        assertEquals(1, r.affectedRowCount(BookStore::class))
        assertTrue(r.totalAffectedRowCount >= 1)
        assertEquals(0, sqlClient.deleteById(Book::class, 12345L).totalAffectedRowCount)
        assertFailsWith<SaveException.CannotDissociateTarget> {
            sqlClient.deleteById(BookStore::class, 2L) { setDissociateAction(Book::store, DissociateAction.CHECK) }
        }
        assertEquals(9, sqlClient.deleteById(TreeNode::class, 1L).affectedRowCount(TreeNode::class))
    }

    @Test
    fun statements() {
        val count = sqlClient.createUpdate(Author::class) {
            set(table.firstName, concat(table.firstName, value("*")))
            set(table.gender, Gender.MALE)
            where(table.firstName eq "Dan")
        }.execute()
        assertEquals(1, count)
        val rows: List<Tuple2<Long, String>> = sqlClient.createUpdateReturning(Author::class) {
            set(table.firstName, "Daniel")
            where(table.firstName eq "Dan*")
            returning(table.id, table.firstName)
        }.execute()
        assertEquals("Daniel", rows[0]._2)
        sqlClient.deleteById(Author::class, 3L)
        assertEquals(0, sqlClient.executeUpdate(Author::class) { set(table.lastName, "X"); where(table.id eq 3L) })
        assertEquals(1, sqlClient.filters { setBehavior(LogicalDeletedBehavior.IGNORED) }
            .executeUpdate(Author::class) { set(table.lastName, "X"); where(table.id eq 3L) })

        assertEquals(2, sqlClient.createDelete(Book::class) { where(table.name eq "Effective TypeScript") }.execute())
        assertEquals(4, sqlClient.executeDelete(Book::class) { where(table.store.name eq "MANNING"); setMode(DeleteMode.PHYSICAL) })
        assertEquals(0, sqlClient.executeDelete(Book::class) { where(table.edition gt 5); disableDissociation() })
    }

    @Test
    fun insertFromSelectAndAssociations() {
        val source = baseTableSymbol {
            sqlClient.createBaseQuery {
                select(CourseRowMapper.id(value(100L)).name(value("Math")))
            }
        }
        val inserted: Int = sqlClient.createInsert(Course::class, source) {
            set(table.id, sourceTable.id)
            set(table.name, sourceTable.name)
            onConflictDoNothing(table.id)
        }.execute()
        assertEquals(1, inserted)
        val source2 = baseTableSymbol {
            sqlClient.createBaseQuery { select(CourseRowMapper.id(value(100L)).name(value("Physics"))) }
        }
        val upserted: List<Tuple2<Long, String>> = sqlClient.createUpsertReturning(Course::class, source2) {
            key(table.id, sourceTable.id)
            merge(table.name, sourceTable.name)
            updateWhere(table.name ne sourceTable.name)
            returning(table.id, table.name)
        }.execute()
        assertEquals("Physics", upserted.single()._2)
        val stores = baseTableSymbol {
            sqlClient.createBaseQuery(BookStore::class) {
                where(table.name eq "MANNING")
                select(table)
            }
        }
        assertEquals(1, sqlClient.createInsert(Course::class, stores) {
            set(table.id, sourceTable.id)
            set(table.name, sourceTable.name)
        }.execute())
        val pairs = baseTableSymbol {
            sqlClient.createBaseQuery { select(BookAuthorPairMapper.bookId(value(4L)).authorId(value(1L))) }
        }
        assertEquals(1, sqlClient.createInsert(Book::authors, pairs) {
            set(table.sourceId, sourceTable.bookId)
            set(table.targetId, sourceTable.authorId)
            onConflictDoNothing()
        }.execute())

        val bookAuthors = sqlClient.getAssociations(Book::authors)
        assertEquals(1, bookAuthors.insert(5L, 1L))
        assertEquals(0, bookAuthors.insertIfAbsent(5L, 1L))
        bookAuthors.insertAll(listOf(6L), listOf(1L, 3L))
        bookAuthors.replace(6L, 3L)
        assertEquals(1, bookAuthors.delete(6L, 3L))
        bookAuthors.deleteAll(listOf(4L, 5L), listOf(1L))
        assertEquals(0, bookAuthors.reverse().insertIfAbsent(3L, 4L))   // (author 3, book 4) already linked
        sqlClient.save(Book { id = 4L; authorIds = listOf(1L, 3L) }, SaveMode.UPDATE_ONLY, AssociatedSaveMode.REPLACE)
        assertEquals(setOf(1L, 3L), sqlClient.findById(newFetcher(Book::class).by { authorIds() }, 4L)!!.authorIds.toSet())
    }
}
