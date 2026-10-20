package com.example

import com.example.model.*
import com.example.runtime.TenantFilter
import org.babyfish.jimmer.UnloadedException
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.cache.Cache
import org.babyfish.jimmer.sql.cache.CacheAbandonedCallback
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineValueBinder
import org.babyfish.jimmer.sql.cache.chain.ChainCacheBuilder
import org.babyfish.jimmer.sql.event.TriggerType
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.cache.AbstractKCacheFactory
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.runtime.Executor
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.*

class CacheSnippetsTest {

    private val sqls = mutableListOf<String>()

    private fun <K, V> caffeine(type: ImmutableType): Cache<K, V> =
        ChainCacheBuilder<K, V>()
            .add(CaffeineValueBinder.forObject<K, V>(type).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
            .build()

    private fun <K, V> caffeine(prop: ImmutableProp): Cache<K, V> =
        ChainCacheBuilder<K, V>()
            .add(CaffeineValueBinder.forProp<K, V>(prop).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
            .build()

    private fun client(db: String, tenantFilter: Boolean = false): KSqlClient {
        val log = Executor.log()
        return TestSupport.client(db, tenantFilter = tenantFilter) {
            setExecutor(object : Executor by log {
                override fun <R> execute(args: Executor.Args<R>): R {
                    sqls += args.sql
                    return log.execute(args)
                }
            })
            setTriggerType(TriggerType.TRANSACTION_ONLY)
            addCacheAbandonedCallback(CacheAbandonedCallback.log())
            setCacheFactory(object : AbstractKCacheFactory() {
                override fun createObjectCache(type: ImmutableType): Cache<*, *> =
                    caffeine<Any, Any>(type)
                override fun createAssociatedIdListCache(prop: ImmutableProp): Cache<*, List<*>> =
                    caffeine<Any, List<*>>(prop)
                override fun createResolverCache(prop: ImmutableProp): Cache<*, *> =
                    caffeine<Any, Any>(prop)
            })
        }
    }

    @Test
    fun objectAssociationAndResolverCaches() {
        val sqlClient = client("kcache1")
        assertEquals("Learning GraphQL", sqlClient.findById(Book::class, 1L)!!.name)
        var before = sqls.size
        sqlClient.findById(Book::class, 1L)
        assertEquals(before, sqls.size)                                   // cache hit

        val fetcher = newFetcher(BookStore::class).by {
            name()
            books { name(); edition() }                                   // orderedProps (name, edition) required
            avgPrice()
        }
        assertFailsWith<UnloadedException> {
            sqlClient.findAll(newFetcher(BookStore::class).by { books { name() } })
        }
        sqlClient.findAll(fetcher) { orderBy(table.id.asc()) }
        before = sqls.size
        val stores = sqlClient.findAll(fetcher) { orderBy(table.id.asc()) }
        assertEquals(before + 1, sqls.size)                               // root query only
        assertEquals(4, stores[0].books.size)

        sqlClient.save(Book { id = 1L; price = BigDecimal("100.00") }, SaveMode.UPDATE_ONLY)
        val avg = sqlClient.findById(newFetcher(BookStore::class).by { avgPrice() }, 1L)!!.avgPrice
        assertEquals(0, BigDecimal("69.75").compareTo(avg), avg.toString())

        sqlClient.save(Book { id = 1L; storeId = 2L }, SaveMode.UPDATE_ONLY)
        val withBooks = newFetcher(BookStore::class).by { books { name(); edition() } }
        assertEquals(3, sqlClient.findById(withBooks, 1L)!!.books.size)
        assertEquals(3, sqlClient.findById(withBooks, 2L)!!.books.size)

        sqlClient.caches.getObjectCache<Long, Book>(Book::class)!!.delete(1L)
        sqlClient.caches.getPropertyCache<Long, List<Long>>(BookStore::books)!!.deleteAll(listOf(1L, 2L))
        before = sqls.size
        sqlClient.caches { disableAll() }.findById(Book::class, 2L)
        sqlClient.caches { disable(Book::class) }.findById(Book::class, 2L)
        sqlClient.caches { disable(BookStore::books) }.findById(withBooks, 1L)
        assertEquals(before + 3, sqls.size)
    }

    @Test
    fun objectCacheHitIgnoresFilters() {
        val sqlClient = client("kcache2", tenantFilter = true)
        assertTrue(sqlClient.createQuery(Book::class) { where(table.id eq 2L); select(table) }.execute().isEmpty())
        assertNull(sqlClient.findById(Book::class, 2L))
        assertNotNull(sqlClient.filters { disableByTypes(TenantFilter::class) }.findById(Book::class, 2L))
        assertNotNull(sqlClient.findById(Book::class, 2L))
    }
}
