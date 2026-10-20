package com.example

import com.example.model.*
import com.example.model.common.TenantAware
import com.example.model.common.TenantAwareDraft
import com.example.model.common.tenant
import com.example.runtime.TenantFilter
import org.babyfish.jimmer.jackson.codec.JsonCodec
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.DraftPreProcessor
import org.babyfish.jimmer.sql.DraftInterceptor
import org.babyfish.jimmer.meta.TypedProp
import com.example.model.common.BaseEntity
import com.example.model.common.BaseEntityDraft
import com.example.model.common.BaseEntityProps
import java.time.LocalDateTime
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.event.EntityEvent
import org.babyfish.jimmer.sql.event.TriggerType
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.event.getChangedRef
import org.babyfish.jimmer.sql.kt.event.isChanged
import org.babyfish.jimmer.sql.kt.filter.KCacheableFilter
import org.babyfish.jimmer.sql.kt.filter.KFilter
import org.babyfish.jimmer.sql.kt.filter.KFilterArgs
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior
import java.math.BigDecimal
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheableTenantFilter : KCacheableFilter<TenantAware> {

    override fun filter(args: KFilterArgs<TenantAware>) {
        TenantFilter.CURRENT_TENANT.get()?.let {
            args.apply { where(table.tenant eq it) }
        }
    }

    override fun getParameters(): SortedMap<String, Any>? =
        TenantFilter.CURRENT_TENANT.get()?.let { sortedMapOf("tenant" to it) }

    override fun isAffectedBy(e: EntityEvent<*>): Boolean =
        e.isChanged(TenantAware::tenant)
}

class CheapBookFilter : KFilter<Book> {
    override fun filter(args: KFilterArgs<Book>) {
        args.apply {
            where(table.price lt BigDecimal(60))
        }
    }
}

class TenantPreProcessor : DraftPreProcessor<TenantAwareDraft> {
    override fun beforeSave(draft: TenantAwareDraft) {
        if (!isLoaded(draft, TenantAware::tenant)) {
            draft.tenant = "pre"
        }
    }
    override fun ignoreIdOnly(): Boolean = true
}

class AuditInterceptor : DraftInterceptor<BaseEntity, BaseEntityDraft> {

    val seenCreatedTimes = mutableListOf<LocalDateTime?>()

    override fun beforeSave(draft: BaseEntityDraft, original: BaseEntity?) {
        seenCreatedTimes += original?.createdTime          // loaded because of dependencies()
        draft.modifiedTime = LocalDateTime.now()
    }

    override fun dependencies(): Collection<TypedProp<BaseEntity, *>> =
        listOf(BaseEntityProps.CREATED_TIME)

    override fun ignoreIdOnly(): Boolean = true
}

class FilterEventSnippetsTest {

    @Test
    fun interceptor() {
        val audit = AuditInterceptor()
        val sqlClient = TestSupport.client("kaudit") { addDraftInterceptor(audit) }
        sqlClient.save(Book { id = 1L; price = BigDecimal.TEN }, SaveMode.UPDATE_ONLY)
        assertEquals(1, audit.seenCreatedTimes.size)
        assertNotNull(audit.seenCreatedTimes[0])
        sqlClient.save(Book { id = 2L }, SaveMode.UPDATE_ONLY)  // id-only root skipped
        assertEquals(1, audit.seenCreatedTimes.size)
    }


    private fun bookCount(sqlClient: org.babyfish.jimmer.sql.kt.KSqlClient) =
        sqlClient.createQuery(Book::class) { select(count(table)) }.fetchOne()

    @Test
    fun filters() {
        val cheap = CheapBookFilter()
        val sqlClient = TestSupport.client("kfilter1", tenantFilter = true) {
            addDisabledFilters(cheap)
        }
        assertEquals(3L, bookCount(sqlClient))                                    // tenant a: 1, 3, 5
        val noTenant = sqlClient.filters { disableByTypes(TenantFilter::class) }
        assertEquals(6L, bookCount(noTenant))
        assertEquals(3L, bookCount(sqlClient))                                    // original unchanged
        val cheapOnly = noTenant.filters { enable(cheap) }
        assertEquals(3L, bookCount(cheapOnly))                                    // 50, 55, 51
        assertEquals(2L, bookCount(sqlClient.filters { enable(cheap) }))          // 50, 51

        // fetcher-loaded associations are filtered too
        val store = sqlClient.findById(newFetcherBooks(), 1L)!!
        assertEquals(listOf(1L, 3L), store.books.map { it.id }.sorted())

        // cacheable filter compiles and filters
        val c2 = TestSupport.client("kfilter2") { addFilters(CacheableTenantFilter()) }
        assertEquals(3L, bookCount(c2))
    }

    private fun newFetcherBooks() =
        org.babyfish.jimmer.sql.kt.fetcher.newFetcher(BookStore::class).by {
            books { name() }
        }

    @Test
    fun logicalDeletion() {
        val sqlClient = TestSupport.client("klogical")
        sqlClient.deleteById(Author::class, 4L)
        val authorCount = { c: org.babyfish.jimmer.sql.kt.KSqlClient ->
            c.createQuery(Author::class) { select(count(table)) }.fetchOne()
        }
        assertEquals(3L, authorCount(sqlClient))
        assertEquals(4L, authorCount(sqlClient.filters { setBehavior(LogicalDeletedBehavior.IGNORED) }))
        assertEquals(1L, authorCount(sqlClient.filters { setBehavior(LogicalDeletedBehavior.REVERSED) }))
        assertEquals(4L, authorCount(sqlClient.filters { setBehavior(Author::class, LogicalDeletedBehavior.IGNORED) }))

        // restore
        val restored = sqlClient
            .filters { setBehavior(LogicalDeletedBehavior.IGNORED) }
            .executeUpdate(Author::class) {
                set(table.deleted, false)
                where(table.id eq 4L)
            }
        assertEquals(1, restored)
        assertEquals(4L, authorCount(sqlClient))
    }

    @Test
    fun preProcessor() {
        val sqlClient = TestSupport.client("kpre") {
            addDraftPreProcessor(TenantPreProcessor())
        }
        val saved = sqlClient.save(
            Book {
                name = "Pre"
                edition = 1
                price = BigDecimal.ONE
                tenant = "x"
            },
            SaveMode.INSERT_ONLY
        ).modifiedEntity
        assertEquals("x", saved.tenant)                       // loaded → kept
    }

    @Test
    fun transactionTriggers() {
        val sqlClient = TestSupport.client("ktrigger") {
            setTriggerType(TriggerType.TRANSACTION_ONLY)
        }
        val priceChanges = mutableListOf<String>()
        var connectionSeen = false
        sqlClient.triggers.addEntityListener(Book::class) { e ->
            if (e.isChanged(Book::price)) {
                val ref = e.getChangedRef(Book::price)!!
                priceChanges += "${ref.oldValue} -> ${ref.newValue}"
            }
            connectionSeen = e.connection != null
        }
        val types = mutableListOf<String>()
        sqlClient.triggers.addEntityListener { e -> types += "${e.type}:${e.immutableType}" }
        sqlClient.triggers.addEntityListener(Book::class) { e ->
            val old: Book? = e.oldEntity
            val new: Book? = e.newEntity
            if (old != null && new != null) types += "old/new"
        }
        val links = mutableListOf<String>()
        sqlClient.triggers.addAssociationListener(Book::authors) { e ->
            links += "${e.sourceId}: -${e.detachedTargetId} +${e.attachedTargetId}"
        }
        sqlClient.save(Book { id = 1L; price = BigDecimal("60.00") }, SaveMode.UPDATE_ONLY)
        assertEquals(listOf("50.00 -> 60.00"), priceChanges)
        assertTrue(connectionSeen)
        assertTrue(types.contains("old/new"), types.toString())
        assertTrue(types.any { it.startsWith("UPDATE:") }, types.toString())
        sqlClient.getAssociations(Book::authors).insert(1L, 3L)
        assertEquals(listOf("1: -null +3"), links)
    }

    @Test
    fun binLog() {
        val sqlClient = TestSupport.client("kbinlog") {     // BINLOG_ONLY (default)
            // 0.12.4 casts BigDecimal/BigInteger binlog values with asInt() -> ClassCastException
            setBinLogPropReader(BigDecimal::class) { _, node -> BigDecimal(node.castTo(String::class.java)) }
        }
        var event: EntityEvent<Book>? = null
        sqlClient.triggers.addEntityListener(Book::class) { event = it }
        val reader = JsonCodec.jsonCodec().treeReader()
        sqlClient.binLog.accept(
            "book",
            reader.read("""{"id": 1, "price": 50.00}"""),
            reader.read("""{"id": 1, "price": 70.00}""")
        )
        val e = assertNotNull(event)
        assertEquals(1L, e.id)
        assertEquals(0, BigDecimal("70").compareTo(e.getChangedRef(Book::price)!!.newValue))
        assertNull(e.connection)
    }
}
