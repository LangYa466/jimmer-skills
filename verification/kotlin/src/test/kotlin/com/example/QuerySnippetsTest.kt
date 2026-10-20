package com.example

import com.example.mapping.*
import com.example.model.*
import org.babyfish.jimmer.Page
import org.babyfish.jimmer.sql.ast.LikeMode
import org.babyfish.jimmer.sql.ast.TypeMatchMode
import org.babyfish.jimmer.sql.ast.query.LockMode
import org.babyfish.jimmer.sql.ast.query.LockWait
import org.babyfish.jimmer.sql.ast.tuple.Tuple2
import org.babyfish.jimmer.sql.association.Association
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.baseTableSymbol
import org.babyfish.jimmer.sql.kt.ast.query.cteBaseTableSymbol
import org.babyfish.jimmer.sql.kt.ast.query.whereIfNotNull
import org.babyfish.jimmer.sql.kt.ast.table.makeOrders
import org.babyfish.jimmer.sql.kt.ast.table.source
import org.babyfish.jimmer.sql.kt.ast.table.target
import org.babyfish.jimmer.sql.kt.createQuery
import org.babyfish.jimmer.sql.kt.executeQuery
import org.babyfish.jimmer.sql.kt.exists
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import java.math.BigDecimal
import kotlin.test.*

class QuerySnippetsTest {

    private val sqlClient = TestSupport.client(
        "kq",
        "create table client(id bigint primary key, client_type varchar(10) not null, name varchar(20) not null, tax_code varchar(20), first_name varchar(20), last_name varchar(20))",
        "insert into client values(1, 'ORG', 'Acme', 'T1', null, null), (2, 'Person', 'Bob', null, 'Bob', 'Lee')"
    )

    @Test
    fun basics() {
        val books: List<Book> = sqlClient.createQuery(Book::class) {
            where(table.name eq "Learning GraphQL")
            orderBy(table.name.asc(), table.edition.desc())
            select(table)
        }.limit(10).execute()
        assertEquals(3, books.size)
        val names: List<String> = sqlClient.executeQuery(Book::class) { select(table.name) }
        assertEquals(6, names.size)
        val viaReified = sqlClient.createQuery<Book, _> { select(table.id) }.execute()
        assertEquals(6, viaReified.size)

        val q = sqlClient.createQuery(Book::class) {
            jdbc { fetchSize = 500; queryTimeout = 30 }
            orderBy(table.id)
            select(table)
        }
        assertNotNull(q.fetchFirst())
        assertTrue(q.exists())
        assertEquals(6, q.fetchUnlimitedCount())
        val page: Page<Book> = q.fetchPage(0, 4)
        assertEquals(2, page.totalPageCount)
        assertTrue(q.fetchSlice(4, 0).isHead)
        q.stream().use { assertEquals(6, it.count()) }
        assertEquals(6, q.map { it.name }.size)
        assertTrue(sqlClient.exists(Book::class) { where(table.edition eq 3) })

        val nullName: String? = null
        val keyword: String? = "MANNING"
        val storeIds: List<Long>? = null
        val edition: Int? = 1
        val dyn = sqlClient.createQuery(Book::class) {
            where(table.name `ilike?` nullName)
            where(table.price.`between?`(null, BigDecimal(100)))
            where(table.storeId `valueIn?` storeIds)
            where(or(table.name `eq?` keyword, table.store.name `eq?` keyword))
            whereIfNotNull(edition) { table.edition eq it }
            select(table)
        }.execute()
        assertEquals(1, dyn.size)
        assertEquals(0, sqlClient.createQuery(Book::class) {      // empty list is NOT ignored: 1 = 0
            where(table.storeId `valueIn?` emptyList<Long>())
            select(table.id)
        }.execute().size)

        val preds = sqlClient.createQuery(Book::class) {
            where(table.name ne "X")
            where(table.price.notBetween(BigDecimal(1000), BigDecimal(2000)))
            where(table.name.like("Learn", LikeMode.START))
            where(table.name valueNotIn listOf("A"))
            where(tuple(table.name, table.edition) valueIn listOf(Tuple2("Learning GraphQL", 1), Tuple2("Learning GraphQL", 2)))
            where(and(table.price ge BigDecimal(1), (table.price gt BigDecimal(1000)).not()))
            where += table.storeId.isNotNull()
            select(table.id)
        }.execute()
        assertEquals(2, preds.size)
        val left = sqlClient.createQuery(Book::class) {
            where(table.`store?`.website.isNull())
            select(table.id)
        }.execute()
        assertEquals(6, left.size)
    }

    @Test
    fun subqueriesProjectionsExpressions() {
        val ids = sqlClient.createQuery(Book::class) {
            where(table.id valueIn subQuery(Author::class) {
                where(table.firstName eq "Alex")
                select(table.asTableEx().books.id)
            })
            select(table.id)
        }.execute()
        assertEquals(3, ids.size)
        val stores = sqlClient.createQuery(BookStore::class) {
            val avgPrice = subQuery(Book::class) {
                where(table.store eq parentTable)
                select(avgAsDecimal(table.price))
            }
            select(table, avgPrice)
        }.execute()
        assertEquals(2, stores.size)
        val anyRows = sqlClient.createQuery(Book::class) {
            where(table.id eq any(subQuery(Author::class) {
                where(table.firstName eq "Alex")
                select(table.asTableEx().books.id)
            }))
            select(table.id)
        }.execute()
        assertEquals(3, anyRows.size)

        val rows: List<Tuple2<String, Int>> = sqlClient.createQuery(Book::class) {
            select(table.name, table.edition)
        }.execute()
        val map = rows.associate { it._1 to it._2 }
        assertTrue(map.isNotEmpty())

        val grouped = sqlClient.createQuery(Book::class) {
            groupBy(table.storeId)
            having(avgAsDecimal(table.price).asNonNull() gt BigDecimal(50))
            select(table.storeId, avgAsDecimal(table.price))
        }.execute()
        assertEquals(2, grouped.size)
        val aggs = sqlClient.createQuery(Book::class) {
            select(count(table), count(table.storeId, distinct = true), rowCount(), sum(table.price), min(table.price), max(table.price), avg(table.price))
        }.fetchOne()
        assertEquals(6L, aggs._1)
        assertEquals(2L, aggs._2)

        val ex = sqlClient.createQuery(Author::class) {
            where(table.id eq 1L)
            select(
                concat(table.firstName, value(" "), table.lastName),
                table.firstName.lower(),
                table.firstName.upper(),
                table.firstName.left(2),
                table.firstName.substring(1, 2),
                table.firstName.position("v"),
                nullValue(Boolean::class),
                constant(1)
            )
        }.fetchOne()
        assertEquals("Eve Procello", ex._1)
        val ex2 = sqlClient.createQuery(Book::class) {
            where(table.id eq 1L)
            select(
                table.price + BigDecimal.ONE,
                table.price * BigDecimal.TEN,
                table.`store?`.website.coalesce("N/A"),
                table.`store?`.website.coalesce().or(table.name).end(),
                case().match(table.price lt BigDecimal(30), "Cheap").match(table.price gt BigDecimal(70), "Expensive").otherwise("Normal"),
                case(table.name).match("Learning GraphQL", "Matched").otherwise("Other"),
                sql(Int::class, "rank() over(order by %e desc)") { expression(table.price) },
                sqlNullable(String::class, "upper(%e)") { expression(table.name) }
            )
        }.fetchOne()
        assertEquals("N/A", ex2._3)
        assertEquals("Learning GraphQL", ex2._4)
        assertEquals("Normal", ex2._5)
        assertEquals("Matched", ex2._6)
        val regex = sqlClient.createQuery(Author::class) {
            where(sql(Boolean::class, "regexp_like(%e, %v)") { expression(table.firstName); value("^Ev") })
            select(table.id)
        }.execute()
        assertEquals(1, regex.size)
    }

    @Test
    fun orderingPagingLockingSetOps() {
        val sorted = sqlClient.createQuery(Book::class) {
            orderBy(table.makeOrders("store.name asc, name, edition desc"))
            select(table.id)
        }.execute()
        assertEquals(6, sorted.size)
        val q1 = sqlClient.createQuery(Book::class) { where(table.edition eq 1); select(table.id) }
        val q2 = sqlClient.createQuery(Book::class) { where(table.edition eq 2); select(table.id) }
        assertEquals(5, (q1 union q2).execute().size)
        assertEquals(5, (q1 unionAll q2).execute().size)
        sqlClient.createQuery(Book::class) { where(table.id eq 1L); select(table) }.forUpdate().execute()
        sqlClient.createQuery(Book::class) { where(table.id eq 1L); select(table) }.forUpdate(LockMode.UPDATE, LockWait.DEFAULT).hint(null).execute()
        val distinct = sqlClient.createQuery(Book::class) {
            where(table.asTableEx().authors.firstName ilike "e")
            select(table.id)
        }.distinct().execute()
        assertTrue(distinct.isNotEmpty())
    }

    @Test
    fun associationsWeakJoinBaseQueriesInheritance() {
        val rows: List<Association<Book, Author>> = sqlClient.queries.forList(Book::authors) {
            where(table.target.firstName eq "Alex")
            select(table)
        }.execute()
        assertEquals(3, rows.size)
        assertNotNull(rows[0].source())

        val weak = sqlClient.createQuery(Book::class) {
            where(table.asTableEx().weakJoin(BookAuthorJoin::class).firstName eq "Alex")
            select(table.id)
        }.distinct().execute()
        assertTrue(weak.isEmpty())
        val weakLambda = sqlClient.createQuery(Book::class) {
            where(table.asTableEx().weakJoin(Author::class) { source.name eq target.lastName }.firstName eq "Alex")
            select(table.id)
        }.distinct().execute()
        assertTrue(weakLambda.isEmpty())

        val ranked = baseTableSymbol {
            sqlClient.createBaseQuery(BookStore::class) {
                selections
                    .add(table)
                    .add(sql(Int::class, "dense_rank() over(order by %e desc)") {
                        expression(subQuery(Book::class) {
                            where(table.storeId eq parentTable.id)
                            select(rowCount())
                        })
                    })
            }
        }
        val top: List<BookStore> = sqlClient.createQuery(ranked) {
            where(table._2 le 2)
            select(table._1.fetchBy { allScalarFields() })
        }.execute()
        assertEquals(2, top.size)

        val tree = cteBaseTableSymbol {
            sqlClient.createBaseQuery(TreeNode::class) {
                where(table.parentId.isNull())
                selections.add(table).add(constant(1))
            }.unionAllRecursively {
                sqlClient.createBaseQuery(TreeNode::class, it, { source.parentId eq target._1.id }) {
                    selections.add(table).add(recursive._2 + 1)
                }
            }
        }
        val levels = sqlClient.createQuery(tree) {
            orderBy(table._2, table._1.name)
            select(table._1.fetchBy { name() }, table._2)
        }.execute()
        assertEquals(9, levels.size)

        val orgs = sqlClient.createQuery(Client::class) {
            where(table.asTableEx().instanceOf(Organization::class))
            select(table.id, table.asTableEx().treatAs(Organization::class).taxCode)
        }.execute()
        assertEquals("T1", orgs.single()._2)
        assertFails {
            sqlClient.createQuery(Client::class) { typeMatchMode(TypeMatchMode.EXACT); select(table) }.execute()
        }
        assertEquals(1, sqlClient.createQuery(Organization::class) { typeMatchMode(TypeMatchMode.EXACT); select(table) }.execute().size)
        val fetched = sqlClient.findAll(newFetcher(Client::class).by { name(); forType(Organization::class) { taxCode() } })
        assertEquals("T1", (fetched.first { it is Organization } as Organization).taxCode)
    }
}
