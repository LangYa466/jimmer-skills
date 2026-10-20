package com.example

import com.example.dto.*
import com.example.dto.catalog.BookCard
import com.example.model.*
import com.example.runtime.BaseEntityDraftInterceptor
import com.example.runtime.TenantDraftInterceptor
import com.example.runtime.TenantFilter
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.ImmutableObjects
import org.babyfish.jimmer.sql.kt.ast.table.makeOrders
import org.babyfish.jimmer.kt.makeIdOnly
import org.babyfish.jimmer.sql.DissociateAction
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.H2Dialect
import org.babyfish.jimmer.sql.exception.SaveException
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.SortDsl
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.kt.exists
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.Executor
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior
import org.babyfish.jimmer.sql.ast.LikeMode
import org.h2.jdbcx.JdbcDataSource
import java.math.BigDecimal
import kotlin.test.*

class JimmerKotlinTest {

    private lateinit var sqlClient: KSqlClient

    @BeforeTest
    fun init() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:kt;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true")
        }
        dataSource.connection.use { con ->
            val sql = javaClass.getResourceAsStream("/schema.sql")!!.readBytes().toString(Charsets.UTF_8)
            con.createStatement().use { stmt ->
                sql.split(";").filter { it.isNotBlank() }.forEach { stmt.execute(it) }
            }
        }
        sqlClient = newKSqlClient {
            setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
            setDialect(H2Dialect())
            setExecutor(Executor.log())
            addDraftInterceptors(BaseEntityDraftInterceptor(), TenantDraftInterceptor())
            addFilters(TenantFilter())
        }
        TenantFilter.CURRENT_TENANT.set("a")
    }

    // ---------------- Draft ----------------

    @Test
    fun testDraft() {
        val book = Book {
            name = "SQL in Action"
            edition = 1
            price = BigDecimal("49.9")
            store { id = 1L }
            authors().addBy { id = 1L }
            authors().addBy { id = 2L }
        }
        assertFalse(isLoaded(book, Book::id))
        assertEquals(1L, book.store!!.id)

        val book2 = book.copy {
            storeId = 2L
            price = BigDecimal("59.9")
        }
        assertEquals(2L, book2.store!!.id)
        assertEquals(BigDecimal("49.9"), book.price)

        val book3 = Book(book) { edition = 2 }
        assertEquals(2, book3.edition)

        assertTrue(ImmutableObjects.isIdOnly(makeIdOnly(Book::class, 10L)))

        val tree = TreeNode {
            name = "Root"
            childNodes().addBy { name = "Child-1" }
            childNodes().addBy { name = "Child-2" }
        }
        val modified = tree.copy {
            childNodes()[0].name = "Child-1*"
        }
        assertEquals("Child-1*", modified.childNodes[0].name)
        println(book2)
    }

    // ---------------- Query DSL ----------------

    @Test
    fun testQueryDsl() {
        val name: String? = "graphql"
        val minPrice: BigDecimal? = null
        val books = sqlClient
            .createQuery(Book::class) {
                where(table.name `ilike?` name)
                where(table.price.`between?`(minPrice, BigDecimal(100)))
                where(table.store.name `eq?` "O'REILLY")
                where += table.authors {
                    or(
                        firstName `ilike?` "e",
                        lastName `ilike?` "e"
                    )
                }
                orderBy(table.name.asc(), table.edition.desc())
                select(table)
            }
            .execute()
        assertEquals(2, books.size)

        val sorted = sqlClient.createQuery(Book::class) {
            orderBy(table.makeOrders("store.name asc, name asc, edition desc"))
            select(table)
        }.execute()
        assertTrue(sorted.isNotEmpty())

        val page = sqlClient.createQuery(Book::class) {
            orderBy(table.id)
            select(table)
        }.fetchPage(0, 2)
        assertEquals(3, page.totalRowCount)

        val slice = sqlClient.createQuery(Book::class) {
            orderBy(table.id)
            select(table)
        }.fetchSlice(2, 0)
        assertTrue(slice.isHead)

        val tuples = sqlClient.createQuery(Book::class) {
            where(table.name.like("Graph", LikeMode.START))
            select(table.name, table.edition)
        }.execute()
        assertEquals(1, tuples.size)
        val (bookName, edition) = tuples[0]
        assertEquals("GraphQL in Action", bookName)
        assertEquals(1, edition)

        val count = sqlClient.createQuery(Book::class) {
            select(count(table))
        }.fetchOne()
        assertEquals(3L, count)

        val avgPrices = sqlClient.createQuery(Book::class) {
            where(table.storeId.isNotNull())
            groupBy(table.storeId)
            select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
        }.execute()
        assertEquals(2, avgPrices.size)

        val ids = sqlClient.createQuery(Book::class) {
            where(
                table.id valueIn subQuery(Author::class) {
                    where(table.firstName eq "Alex")
                    select(table.asTableEx().books.id)
                }
            )
            select(table.id)
        }.execute()
        assertEquals(2, ids.size)

        val existsBooks = sqlClient.createQuery(Book::class) {
            where(
                exists(
                    wildSubQuery(Author::class) {
                        where(
                            table.asTableEx().books eq parentTable,
                            table.firstName eq "Alex"
                        )
                    }
                )
            )
            select(table)
        }.execute()
        assertEquals(2, existsBooks.size)

        val joinedIds = sqlClient.createQuery(Book::class) {
            where(table.asTableEx().authors.firstName ilike "e")
            select(table.id)
        }.distinct().execute()
        assertTrue(joinedIds.isNotEmpty())

        val newest = sqlClient.createQuery(Book::class) {
            where(
                tuple(table.name, table.edition) valueIn subQuery(Book::class) {
                    groupBy(table.name)
                    select(table.name, max(table.edition).asNonNull())
                }
            )
            select(table)
        }.execute()
        assertTrue(newest.isNotEmpty())

        val rows = sqlClient.createQuery(Book::class) {
            select(
                case()
                    .match(table.price lt BigDecimal(60), "Cheap")
                    .otherwise("Expensive"),
                table.`store?`.website.coalesce("N/A"),
                sql(Int::class, "length(%e)") {
                    expression(table.name)
                }
            )
        }.execute()
        assertEquals(3, rows.size)

        val stats = sqlClient.createQuery(Book::class) {
            where(table.storeId.isNotNull())
            groupBy(table.storeId)
            select(
                StoreStatisticsMapper
                    .storeId(table.storeId.asNonNull())
                    .bookCount(rowCount())
                    .avgPrice(avgAsDecimal(table.price))
            )
        }.execute()
        assertEquals(2, stats.size)

        assertTrue(sqlClient.exists(Book::class) { where(table.edition eq 3) })
    }

    // ---------------- Fetcher ----------------

    @Test
    fun testFetcher() {
        val detailFetcher = newFetcher(Book::class).by {
            allScalarFields()
            tenant(false)
            store {
                name()
                avgPrice()
            }
            authors({
                batch(16)
                filter {
                    orderBy(table.firstName)
                }
            }) {
                firstName()
                lastName()
            }
        }
        val books = sqlClient.createQuery(Book::class) {
            where(table.edition eq 1)
            select(table.fetch(detailFetcher))
        }.execute()
        assertTrue(books.isNotEmpty())
        assertTrue(isLoaded(books[0].store!!, BookStore::avgPrice))
        assertFalse(isLoaded(books[0], Book::tenant))

        val joined = sqlClient.createQuery(Book::class) {
            select(
                table.fetchBy {
                    name()
                    store(ReferenceFetchType.JOIN_ALWAYS) {
                        name()
                    }
                    authorIds()
                }
            )
        }.execute()
        assertTrue(joined.isNotEmpty())

        val roots = sqlClient.createQuery(TreeNode::class) {
            where(table.parentId.isNull())
            select(
                table.fetchBy {
                    name()
                    `childNodes*` {
                        depth(2)
                    }
                }
            )
        }.execute()
        assertEquals(2, roots[0].childNodes.size)

        val deep = sqlClient.findById(
            newFetcher(TreeNode::class).by {
                name()
                `childNodes*` {
                    recursive {
                        entity.name != "Clothing"
                    }
                }
            },
            1L
        )
        assertNotNull(deep)

        val author = sqlClient.findById(
            newFetcher(Author::class).by {
                fullName()
                sqlFullName()
            },
            1L
        )!!
        assertEquals("Eve Procello", author.fullName)
        assertEquals("Eve Procello", author.sqlFullName)

        val list = sqlClient.findAll(newFetcher(Book::class).by { name() }) {
            where(table.edition eq 1)
            orderBy(table.name)
        }
        assertTrue(list.isNotEmpty())

        assertNull(sqlClient.findById(Book::class, 999L))
        assertFails { sqlClient.findOneById(Book::class, 999L) }

        val stores = sqlClient.entities.findAll(
            newFetcher(BookStore::class).by {
                allScalarFields()
                books { name() }
            }
        ) {
            asc(BookStore::name)
        }
        assertEquals(2, stores.size)
    }

    // ---------------- DTO ----------------

    @Test
    fun testDto() {
        val views = sqlClient.createQuery(Book::class) {
            where(table.edition eq 1)
            orderBy(table.name)
            select(table.fetch(BookView::class))
        }.execute()
        assertTrue(views.isNotEmpty())

        val detail = sqlClient.findById(BookDetailView::class, 1L)
        assertNotNull(detail)

        val simple = sqlClient.findById(BookSimpleView::class, 1L)!!
        assertEquals(1L, simple.storeId)

        val entity = sqlClient.findById(BookView.METADATA.fetcher, 1L)!!
        val view = BookView(entity)
        assertEquals(entity.name, view.toEntity().name)

        val flat = sqlClient.findById(FlatTreeNodeView::class, 4L)!!
        assertEquals("Food", flat.parentName)

        val card = sqlClient.findById(BookCard::class, 1L)!!
        assertEquals(2, card.authors.size)

        val viewList = sqlClient.findAll(BookView::class) {
            where(table.edition eq 1)
        }
        assertTrue(viewList.isNotEmpty())

        val spec = BookSpecification(name = "graphql", minPrice = BigDecimal(40), authorName = "eve")
        val specBooks = sqlClient.createQuery(Book::class) {
            where(spec)
            select(table)
        }.execute()
        assertEquals(2, specBooks.size)
    }

    // ---------------- Save ----------------

    @Test
    fun testSave() {
        val store = BookStore {
            name = "AMAZON"
            books().addBy {
                name = "C++ Primer"
                edition = 5
                price = BigDecimal("44.02")
            }
            books().addBy {
                name = "Programming RUST"
                edition = 1
                price = BigDecimal("71.99")
                authors().addBy { id = 1L }
            }
        }
        val result = sqlClient.save(store)
        assertTrue(result.isModified)
        val storeId = result.modifiedEntity.id
        assertEquals(2, result.affectedRowCount(Book::class))
        assertEquals(1, result.affectedRowCount(Book::authors))

        val updated = sqlClient.save(
            Book {
                id = 1L
                price = BigDecimal("33.9")
            },
            SaveMode.UPDATE_ONLY
        )
        assertEquals(1, updated.totalAffectedRowCount)

        sqlClient.save(
            Book {
                name = "New Book"
                edition = 1
                price = BigDecimal.TEN
                this.storeId = storeId
            }
        ) {
            setMode(SaveMode.INSERT_ONLY)
            setAssociatedModeAll(AssociatedSaveMode.APPEND)
        }

        val input = BookInput(
            name = "DTO Book",
            edition = 1,
            price = BigDecimal("12.5"),
            storeId = storeId,
            authorIds = listOf(1L, 2L)
        )
        assertTrue(sqlClient.save(input).modifiedEntity.id > 0)

        sqlClient.save(
            CompositeBookStoreInput(
                name = "COMPOSITE",
                books = listOf(
                    CompositeBookStoreInput.TargetOf_books(
                        name = "Composite Book",
                        edition = 1,
                        price = BigDecimal.ONE,
                        authorIds = listOf(3L)
                    )
                )
            )
        )

        val saved = sqlClient
            .saveCommand(input.toEntity()) {
                setAssociatedMode(Book::authors, AssociatedSaveMode.MERGE)
                setKeyProps(Book::name, Book::edition)
            }
            .execute(newFetcher(Book::class).by { name(); authors { firstName() } })
            .modifiedEntity
        assertEquals("DTO Book", saved.name)

        val batch = sqlClient.saveEntities(
            listOf(
                Book { id = 1L; price = BigDecimal.ONE },
                Book { id = 3L; price = BigDecimal("3") }
            ),
            SaveMode.UPDATE_ONLY
        )
        assertEquals(2, batch.items.size)

        assertFailsWith<SaveException.OptimisticLockError> {
            sqlClient.save(
                BookStore {
                    id = 1L
                    website = "https://www.oreilly.com"
                    version = 999
                },
                SaveMode.UPDATE_ONLY
            )
        }

        assertFailsWith<SaveException.NotUnique> {
            sqlClient.save(BookStore { name = "MANNING" }, SaveMode.INSERT_ONLY)
        }

        sqlClient.save(
            Book {
                name = "Learning GraphQL"
                edition = 1
                price = BigDecimal("77")
            }
        ) {
            setUpsertMask(Book::price)
        }
        sqlClient.save(Book { id = 3L; price = BigDecimal("1") }) {
            setMode(SaveMode.UPDATE_ONLY)
            setUpdateWhere(Book::class) {
                newNonNull(Book::price) gt table.price
            }
        }
        sqlClient.save(Book { id = 3L; price = BigDecimal("2.00") }) {
            setMode(SaveMode.UPDATE_ONLY)
            set(Book::price) {
                target.price + newNonNull(Book::price)
            }
        }

        val treeInput = RecursiveTreeInput(
            name = "Root2",
            childNodes = listOf(RecursiveTreeInput(name = "Child", childNodes = emptyList()))
        )
        val root = treeInput.toEntity().copy { parent = null }
        assertTrue(sqlClient.save(root).modifiedEntity.id > 0)
    }

    // ---------------- Delete / DML ----------------

    @Test
    fun testDeleteAndDml() {
        val affected = sqlClient.createUpdate(Author::class) {
            set(table.firstName, concat(table.firstName, value("*")))
            where(table.firstName eq "Dan")
        }.execute()
        assertEquals(1, affected)

        val logical = sqlClient.deleteById(Author::class, 3L)
        assertEquals(1, logical.totalAffectedRowCount)
        assertNull(sqlClient.createQuery(Author::class) {
            where(table.id eq 3L)
            select(table)
        }.fetchOneOrNull())
        val ignored = sqlClient
            .filters { setBehavior(LogicalDeletedBehavior.IGNORED) }
            .createQuery(Author::class) {
                where(table.id eq 3L)
                select(table)
            }
            .fetchOne()
        assertTrue(ignored.deleted)

        val result = sqlClient.deleteById(BookStore::class, 2L) {
            setDissociateAction(Book::store, DissociateAction.SET_NULL)
        }
        assertEquals(1, result.affectedRowCount(BookStore::class))

        sqlClient.createDelete(Book::class) {
            where(table.name eq "Effective TypeScript")
        }.execute()

        val treeResult = sqlClient.deleteById(TreeNode::class, 2L, DeleteMode.PHYSICAL)
        assertTrue(treeResult.affectedRowCount(TreeNode::class) >= 1)
    }

    // ---------------- Transaction ----------------

    @Test
    fun testTransaction() {
        assertFailsWith<IllegalStateException> {
            sqlClient.transaction {
                sqlClient.save(BookStore { name = "TX" }, SaveMode.INSERT_ONLY)
                throw IllegalStateException("rollback")
            }
        }
        val count = sqlClient.createQuery(BookStore::class) {
            where(table.name eq "TX")
            select(count(table))
        }.fetchOne()
        assertEquals(0L, count)
    }
}
