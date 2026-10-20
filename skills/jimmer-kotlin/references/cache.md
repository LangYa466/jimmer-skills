# Caching (Kotlin)

## Contents
- [What Jimmer caches](#what-jimmer-caches)
- [Enabling caches (Redis + Caffeine)](#enabling-caches-redis--caffeine)
- [Local-only caches](#local-only-caches)
- [How caches are used](#how-caches-are-used)
- [Consistency and invalidation](#consistency-and-invalidation)
- [Calculated (transient) property caches](#calculated-transient-property-caches)
- [Multi-view caches and filters](#multi-view-caches-and-filters)
- [Bypassing and inspecting caches](#bypassing-and-inspecting-caches)

## What Jimmer caches

| Cache | Key → value | `CacheFactory` method |
|---|---|---|
| Object cache | id → entity (scalars + FK ids only) | `createObjectCache(type: ImmutableType)` |
| Association cache (reference) | id → target id | `createAssociatedIdCache(prop: ImmutableProp)` |
| Association cache (list) | id → list of target ids | `createAssociatedIdListCache(prop: ImmutableProp)` |
| Calculated cache | id → `@Transient(Resolver::class)` value | `createResolverCache(prop: ImmutableProp)` |

Results of your own `createQuery { }` (the aggregate roots) are **never** cached — only id lookups (`findById`,
`findByIds`, `findMapByIds`) and what fetchers load *after* the root query (associations, transient props). A
many-to-one backed by a real FK needs no association cache (the FK column is the target id).

## Enabling caches (Redis + Caffeine)

With the Spring Boot starter declare a `CacheFactory` bean; without Spring call `setCacheFactory(...)` in
`newKSqlClient { }`. Kotlin base classes live in `org.babyfish.jimmer.sql.kt.cache`: `KCacheFactory` (all methods
default to `null`) and `AbstractKCacheFactory` (adds the protected `filterState`).

```kotlin
@Configuration
class CacheConfig {

    @Bean
    fun cacheFactory(connectionFactory: RedisConnectionFactory): CacheFactory {
        val creator = RedisCacheCreator(connectionFactory, JsonCodec.jsonCodec())   // org.babyfish.jimmer.spring.cache
            .withRemoteDuration(Duration.ofHours(1))           // default 30 min (+ random 30%)
            .withLocalCache(100, Duration.ofMinutes(5))        // Caffeine level 1 (needs caffeine dependency)
            // .withSoftLock(RedissonCacheLocker(redisson), Duration.ofSeconds(30))
            // .withTracking(RedissonCacheTracker(redisson))  // cross-node local-cache invalidation

        return object : AbstractKCacheFactory() {
            override fun createObjectCache(type: ImmutableType): Cache<*, *>? =
                creator.createForObject<Any, Any>(type)

            override fun createAssociatedIdCache(prop: ImmutableProp): Cache<*, *>? =
                creator.createForProp<Any, Any>(prop, filterState.isAffected(prop.targetType))

            override fun createAssociatedIdListCache(prop: ImmutableProp): Cache<*, List<*>>? =
                creator.createForProp<Any, List<*>>(prop, filterState.isAffected(prop.targetType))

            override fun createResolverCache(prop: ImmutableProp): Cache<*, *>? =
                creator.createForProp<Any, Any>(prop, true)        // resolvers may depend on filters
        }
    }
}
```

- Pass the type arguments explicitly (`createForObject<Any, Any>`): Kotlin cannot infer them from `Cache<*, *>?`.
- Return `null` to leave a type/property uncached.
- `filterState.isAffected(targetType)` tells whether global filters apply to the target → multi-view cache.
- Values are serialized with Jimmer's `JsonCodec`; pre-0.12 docs that pass an `ObjectMapper` to
  `RedisValueBinder` are outdated.
- Dependencies: `spring-boot-starter-data-redis` for `RedisCacheCreator`, `com.github.ben-manes.caffeine:caffeine`
  for local caches, `redisson` for `RedissonCacheLocker/Tracker`.

## Local-only caches

```kotlin
private fun <K, V> caffeine(type: ImmutableType): Cache<K, V> =
    ChainCacheBuilder<K, V>()                                         // org.babyfish.jimmer.sql.cache.chain
        .add(CaffeineValueBinder.forObject<K, V>(type).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
        .build()

private fun <K, V> caffeine(prop: ImmutableProp): Cache<K, V> =
    ChainCacheBuilder<K, V>()
        .add(CaffeineValueBinder.forProp<K, V>(prop).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
        .build()

val sqlClient = newKSqlClient {
    setCacheFactory(object : AbstractKCacheFactory() {
        override fun createObjectCache(type: ImmutableType): Cache<*, *> = caffeine<Any, Any>(type)
        override fun createAssociatedIdListCache(prop: ImmutableProp): Cache<*, List<*>> = caffeine<Any, List<*>>(prop)
        override fun createResolverCache(prop: ImmutableProp): Cache<*, *> = caffeine<Any, Any>(prop)
    })
    addCacheAbandonedCallback(CacheAbandonedCallback.log())
}
```

`setCaches { setCacheFactory(...); setObjectCache(Book::class, cache); setAssociatedListIdCache(BookStore::books, cache) }`
configures individual caches instead. A local-only cache is correct only for a **single** instance (or with
cross-node tracking).

## How caches are used

```kotlin
sqlClient.findById(Book::class, 1L)                     // object cache "Book-1"; misses are re-queried each time
sqlClient.findMapByIds(Book::class, listOf(1L, 2L, 999L))

val stores = sqlClient.findAll(newFetcher(BookStore::class).by {   // root query: always SQL
    name()
    books { name(); edition() }                         // ids from "BookStore.books-<id>", objects from "Book-<id>"
    avgPrice()                                          // "BookStore.avgPrice-<id>"
}) { orderBy(table.id.asc()) }
```

- **Ordered associations need their order props in the child fetcher.** Jimmer sorts cached children in memory by
  `orderedProps`; in 0.12.4 a child fetcher without them (including id-only `books()`) throws `UnloadedException`.
- An association fetched with a property-level `filter { }` bypasses the association cache (`FIELD_FILTER_USED`).
- With an object cache, `findById` returns a cache **hit** without re-checking global filters/logical deletion
  (misses are loaded with filters). Use a query when filter semantics matter, or make the filter a `KShardingFilter`.
- Cache misses are loaded in batches (one SQL for all missing ids).

## Consistency and invalidation

Invalidation is automatic for object and association caches, driven by `sqlClient.getTriggers(false)`:

| Trigger type | What invalidates the cache |
|---|---|
| `BINLOG_ONLY` (default) | only changes you feed into `sqlClient.binLog.accept(...)` from CDC. **Without CDC wiring, nothing is invalidated.** |
| `TRANSACTION_ONLY` | changes made through Jimmer; with Spring the starter stores pending deletions in table `JIMMER_TRANS_CACHE_OPERATOR` and flushes them after commit (needs a real dialect, not `DefaultDialect`) |
| `BOTH` | binlog for caches, plus transaction triggers for your own listeners |

Changing `BOOK.PRICE` evicts `Book-<id>`; changing `BOOK.STORE_ID` evicts `Book.store-<id>`,
`BookStore.books-<old>` and `BookStore.books-<new>`; middle-table changes evict both sides.

Manual eviction:

```kotlin
sqlClient.caches.getObjectCache<Long, Book>(Book::class)?.delete(1L)
sqlClient.caches.getPropertyCache<Long, List<Long>>(BookStore::books)?.deleteAll(listOf(1L, 2L))
```

## Calculated (transient) property caches

Jimmer cannot know what a resolver depends on; override the invalidation hooks (inherited from
`PropCacheInvalidator`):

```kotlin
class BookStoreAvgPriceResolver(
    private val sqlClient: KSqlClient
) : KTransientResolver<Long, BigDecimal> {

    override fun resolve(ids: Collection<Long>): Map<Long, BigDecimal> =
        sqlClient.createQuery(Book::class) {
            where(table.storeId valueIn ids)
            groupBy(table.storeId)
            select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
        }.execute().associate { it._1 to it._2 }

    override fun getDefaultValue(): BigDecimal = BigDecimal.ZERO

    // a book moved between stores
    override fun getAffectedSourceIds(e: AssociationEvent): Collection<*>? =
        if (sqlClient.caches.isAffectedBy(e) && e.immutableProp == BookStore::books.toImmutableProp()) {
            listOf(e.sourceId)
        } else {
            null
        }

    // a book price changed while its store stayed the same
    override fun getAffectedSourceIds(e: EntityEvent<*>): Collection<*>? {
        if (sqlClient.caches.isAffectedBy(e) && !e.isEvict && e.immutableType.javaClass == Book::class.java) {
            val storeId = e.getUnchangedRef(Book::store)?.value?.id    // org.babyfish.jimmer.sql.kt.event.getUnchangedRef
            if (storeId !== null && e.isChanged(Book::price)) {
                return listOf(storeId)
            }
        }
        return null
    }
}
```

- `toImmutableProp()` is `org.babyfish.jimmer.kt.toImmutableProp`.
- `sqlClient.caches.isAffectedBy(e)` filters duplicate notifications (`BOTH` mode); `e.isEvict` events only carry ids.
- `getUnchangedRef(prop)` is `null` if the property changed, otherwise a `Ref` holding the unchanged value.
- Resolvers depending on the current user/filter override `getParameterMapRef()` (multi-view).
- In Spring the resolver is a bean; if injecting `KSqlClient` creates a cycle, inject it `@Lazy`.

## Multi-view caches and filters

If a global filter applies to an association's target, the cache must be keyed by the filter parameters:

- Every filter on that entity must be a `KCacheableFilter`; one plain `KFilter` disables caching for every
  filter-sensitive property. Register `CacheAbandonedCallback.log()` to see why a cache was skipped
  (`CACHEABLE_FILTER_REQUIRED`, `PARAMETERIZED_CACHE_REQUIRED`, `FIELD_FILTER_USED`).
- `getParameters()` is the sub-key; `isAffectedBy(e)` says which changes affect the filtered view.
- `filterState.isAffected(...)` in `AbstractKCacheFactory` picks multi-view caches automatically.

## Bypassing and inspecting caches

```kotlin
val noCache     = sqlClient.caches { disableAll() }
val noBookCache = sqlClient.caches { disable(Book::class) }
val noAssoc     = sqlClient.caches { disable(BookStore::books) }
```

Stale data checklist: is a trigger wired (CDC for `BINLOG_ONLY`)? Was the row changed outside Jimmer under
`TRANSACTION_ONLY`? Does the calculated property implement `getAffectedSourceIds`? Is a clustered app using a local
cache level without tracking?
