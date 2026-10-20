# Caching (Java)

## Contents
- [What Jimmer caches](#what-jimmer-caches)
- [Enabling caches](#enabling-caches)
- [Local-only caches (no Redis)](#local-only-caches-no-redis)
- [How caches are used](#how-caches-are-used)
- [Consistency and invalidation](#consistency-and-invalidation)
- [Calculated (transient) property caches](#calculated-transient-property-caches)
- [Multi-view caches and filters](#multi-view-caches-and-filters)
- [Bypassing and inspecting caches](#bypassing-and-inspecting-caches)

## What Jimmer caches

| Cache | Key → value | Created by `CacheFactory` method |
|---|---|---|
| Object cache | id → entity (scalars + FK ids only) | `createObjectCache(ImmutableType)` |
| Association cache (reference) | id → target id | `createAssociatedIdCache(ImmutableProp)` |
| Association cache (list) | id → list of target ids | `createAssociatedIdListCache(ImmutableProp)` |
| Calculated cache | id → `@Transient(Resolver)` value | `createResolverCache(ImmutableProp)` |

Results of your own `createQuery(...)` (the aggregate roots) are **never** cached — only id lookups
(`findById(s)`, `findMapByIds`) and what object fetchers load *after* the root query (associations, transient props).
A many-to-one backed by a real FK needs no association cache (the FK column is the target id).

## Enabling caches

With the Spring Boot starter, declare a `CacheFactory` bean; without Spring, call `builder.setCacheFactory(...)`.
The 0.12 way is a `CacheCreator` (Redis + optional Caffeine local level) plus `AbstractCacheFactory`:

```java
@Bean
public CacheFactory cacheFactory(RedisConnectionFactory connectionFactory, RedissonClient redissonClient) {
    CacheCreator creator = new RedisCacheCreator(connectionFactory, JsonCodec.jsonCodec())   // org.babyfish.jimmer.spring.cache
            .withRemoteDuration(Duration.ofHours(1))                 // default 30 min (+ random 30%)
            .withLocalCache(100, Duration.ofMinutes(5))              // Caffeine level 1 (needs caffeine dependency)
            .withMultiViewProperties(40, Duration.ofMinutes(2), Duration.ofMinutes(24))
            .withSoftLock(new RedissonCacheLocker(redissonClient), Duration.ofSeconds(30))   // optional
            .withTracking(new RedissonCacheTracker(redissonClient));                         // optional, app clusters

    return new AbstractCacheFactory() {
        @Override
        public Cache<?, ?> createObjectCache(@NotNull ImmutableType type) {
            return creator.createForObject(type);
        }
        @Override
        public Cache<?, ?> createAssociatedIdCache(@NotNull ImmutableProp prop) {
            return creator.createForProp(prop, getFilterState().isAffected(prop.getTargetType()));
        }
        @Override
        public Cache<?, List<?>> createAssociatedIdListCache(@NotNull ImmutableProp prop) {
            return creator.createForProp(prop, getFilterState().isAffected(prop.getTargetType()));
        }
        @Override
        public Cache<?, ?> createResolverCache(@NotNull ImmutableProp prop) {
            return creator.createForProp(prop, true);                 // multi-view: resolvers may depend on filters
        }
    };
}
```

- Return `null` from a method to leave that type/property uncached.
- `getFilterState().isAffected(targetType)` tells whether global filters apply to the target → needs a
  multi-view (parameterized) cache.
- Values are serialized with Jimmer's `JsonCodec` (Jackson 2 or 3). Docs that pass `objectMapper(...)` to
  `RedisValueBinder` show the pre-0.12 API.
- Dependencies: `spring-boot-starter-data-redis` for `RedisCacheCreator`; `com.github.ben-manes.caffeine:caffeine` for
  local caches; `redisson` for `RedissonCacheLocker/Tracker`.

## Local-only caches (no Redis)

`ChainCacheBuilder` composes levels from binders (`LoadingBinder` = self-loading like Caffeine,
`SimpleBinder` = remote like Redis):

```java
JSqlClient sqlClient = JSqlClient.newBuilder()
        .setCacheFactory(new CacheFactory() {
            @Override
            public Cache<?, ?> createObjectCache(@NotNull ImmutableType type) {
                return new ChainCacheBuilder<Object, Object>()
                        .add(CaffeineValueBinder.forObject(type).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
                        .build();
            }
            @Override
            public Cache<?, List<?>> createAssociatedIdListCache(@NotNull ImmutableProp prop) {
                return new ChainCacheBuilder<Object, List<?>>()
                        .add(CaffeineValueBinder.<Object, List<?>>forProp(prop).maximumSize(1024).duration(Duration.ofMinutes(10)).build())
                        .build();
            }
        })
        .build();
```

A local-only cache is only correct for a **single** application instance (or with `withTracking(...)`-style cross-node
invalidation).

## How caches are used

```java
Book book = sqlClient.findById(Book.class, 1L);                 // object cache "Book-1"
Map<Long, Book> books = sqlClient.findMapByIds(Book.class, List.of(1L, 2L, 999L));

List<BookStore> stores = sqlClient.createQuery(store)           // root: always SQL
        .select(store.fetch(BookStoreFetcher.$.name()
                .books(BookFetcher.$.name().edition())          // ids from "BookStore.books-<id>", objects from "Book-<id>"
                .avgPrice()))                                   // "BookStore.avgPrice-<id>"
        .execute();
```

- An association fetched with a property-level filter (`cfg -> cfg.filter(...)`) bypasses the association cache
  (`FIELD_FILTER_USED`) — declare default ordering statically with `@OneToMany(orderedProps = ...)` instead.
- **Ordered associations need their order props in the child fetcher.** With an association cache, Jimmer sorts the
  cached children in memory by `@OneToMany/@ManyToMany(orderedProps = ...)`; in 0.12.4 a child fetcher that lacks
  those properties (including id-only `books()`) throws `UnloadedException`. Fetch e.g.
  `books(BookFetcher.$.name().edition())` when `orderedProps = {name, edition}`.
- With an object cache, `findById(s)` results depend on cache state: a cache miss is loaded with global filters
  applied, but a cache **hit** is returned without re-checking filters/logical deletion (unless the filter is a
  `ShardingFilter`). Use a query when filter semantics matter.
- Cache misses are loaded in batches (one SQL for all missing ids).
- Ids that don't exist are not cached by a local-only (Caffeine) chain — they are queried again next time — but a
  chain with a remote level (Redis) stores them as `null`.

## Consistency and invalidation

Invalidation is automatic for object and association caches, driven by triggers (`sqlClient.getTriggers(false)`):

| Trigger type | What invalidates the cache |
|---|---|
| `BINLOG_ONLY` (default) | only changes you feed to `sqlClient.getBinLog().accept(...)` from CDC (any writer, after commit). **Without CDC wiring, nothing is invalidated.** |
| `TRANSACTION_ONLY` | changes made through Jimmer APIs; Jimmer selects old rows during mutations. By default cache entries are deleted immediately, inside the transaction. To defer deletion until after commit, declare a `CacheOperator` bean `new TransactionCacheOperator()`: it records pending deletions in table `JIMMER_TRANS_CACHE_OPERATOR` (auto-created only by `H2Dialect`, the MySQL-style dialects, `OracleDialect` and `PostgresDialect`) and the starter flushes them after commit, retrying every `jimmer.transaction-cache-operator-fixed-delay` ms (default 5000) |
| `BOTH` | binlog for caches, plus transaction triggers for your own listeners |

Examples of automatic invalidation: changing `BOOK.PRICE` evicts `Book-<id>`; changing `BOOK.STORE_ID` evicts
`Book.store-<id>` and `BookStore.books-<old>` / `BookStore.books-<new>`; inserting into `BOOK_AUTHOR_MAPPING` evicts
`Book.authors-<bookId>` and `Author.books-<authorId>`.

Manual eviction: `sqlClient.getCaches().getObjectCache(Book.class).delete(1L)`,
`sqlClient.getCaches().getPropertyCache(BookStoreProps.BOOKS).deleteAll(List.of(1L, 2L))`.

## Calculated (transient) property caches

Jimmer cannot know what a custom resolver depends on, so the resolver tells it via `PropCacheInvalidator` methods
(inherited by `TransientResolver`):

```java
@Component
public class BookStoreAvgPriceResolver implements TransientResolver<Long, BigDecimal> {

    private final JSqlClient sqlClient;

    public BookStoreAvgPriceResolver(JSqlClient sqlClient) { this.sqlClient = sqlClient; }

    @Override
    public Map<Long, BigDecimal> resolve(Collection<Long> ids) { ... }   // batch: ids -> value

    @Override
    public BigDecimal getDefaultValue() { return BigDecimal.ZERO; }    // for ids missing from the result map

    // a book moved between stores
    @Override
    public Collection<?> getAffectedSourceIds(@NotNull AssociationEvent e) {
        if (sqlClient.getCaches().isAffectedBy(e) && e.getImmutableProp() == BookStoreProps.BOOKS.unwrap()) {
            return Collections.singletonList(e.getSourceId());
        }
        return null;
    }

    // a book price changed while its store stayed the same
    @Override
    public Collection<?> getAffectedSourceIds(@NotNull EntityEvent<?> e) {
        if (sqlClient.getCaches().isAffectedBy(e) && !e.isEvict() && e.getImmutableType().getJavaClass() == Book.class) {
            Ref<BookStore> storeRef = e.getUnchangedRef(BookProps.STORE);
            if (storeRef != null && storeRef.getValue() != null && e.isChanged(BookProps.PRICE)) {
                return Collections.singletonList(storeRef.getValue().id());
            }
        }
        return null;
    }
}
```

- `sqlClient.getCaches().isAffectedBy(e)` filters out duplicate notifications (`BOTH` mode).
- `e.isEvict()` events (cascading evictions) only carry the id — check it before reading other event data.
- `getUnchangedRef(prop)` returns `null` if the property changed, or a `Ref` whose value is the unchanged value.
- Resolvers whose result depends on the current user/filter must override `getParameterMapRef()` (multi-view).
- In Spring the resolver is a bean; inject `JSqlClient` lazily (`@Lazy` or `ObjectProvider`) if you hit a cycle.

## Multi-view caches and filters

If a global filter applies to the target of an association, different users see different association values, so
the cache must be keyed by the filter parameters:

- Every filter on that entity must implement `CacheableFilter<P>`; one non-cacheable filter disables caching for
  every filter-sensitive property. Register `builder.addCacheAbandonedCallback(CacheAbandonedCallback.log())` (or a
  `CacheAbandonedCallback` bean) to be told why a cache was skipped: `CACHEABLE_FILTER_REQUIRED`,
  `PARAMETERIZED_CACHE_REQUIRED` or `FIELD_FILTER_USED`.
- `getParameters()` returns the sub-key (`{"tenant":"a"}`); the logical-deletion filter contributes its own part.
- `isAffectedBy(EntityEvent)` says which entity changes affect the filtered view (e.g. `e.isChanged(TENANT)`).
- Use `creator.createForProp(prop, true)` (Redis hash per key) for such properties — `getFilterState().isAffected(...)`
  computes this automatically in `AbstractCacheFactory`.

## Bypassing and inspecting caches

```java
JSqlClient noCache     = sqlClient.caches(cfg -> cfg.disableAll());
JSqlClient noBookCache = sqlClient.caches(cfg -> cfg.disable(Book.class));
JSqlClient noAssoc     = sqlClient.caches(cfg -> cfg.disable(BookStoreProps.BOOKS));
Cache<Long, Book> bookCache = sqlClient.getCaches().getObjectCache(Book.class);
```

Checklist when "the cache returns stale data": is a trigger actually wired (CDC for `BINLOG_ONLY`)? Was the change
made outside Jimmer while using `TRANSACTION_ONLY`? Does a calculated property implement `getAffectedSourceIds`? Is the
application clustered with a local cache level but no tracker?
