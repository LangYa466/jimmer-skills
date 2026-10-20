# Global filters, logical deletion, draft interceptors and triggers (Kotlin)

## Contents
- [Global filters](#global-filters)
- [Filter marker interfaces](#filter-marker-interfaces)
- [Disabling filters per call](#disabling-filters-per-call)
- [Logical deletion at query time](#logical-deletion-at-query-time)
- [Draft interceptors (audit fields, defaults)](#draft-interceptors-audit-fields-defaults)
- [Draft pre-processors](#draft-pre-processors)
- [Triggers: listening to changes](#triggers-listening-to-changes)
- [BinLog integration](#binlog-integration)

## Global filters

A global filter adds a `where` condition to **every** query of the entities it applies to: root queries,
fetcher-loaded associations, implicit subqueries, `findAll`, update/delete statements. Typical uses: multi-tenancy,
data permissions. Filter a `@MappedSuperclass` so one filter covers many entities (in Kotlin the generic argument is
the type itself, not a `Props` class):

```kotlin
@MappedSuperclass
interface TenantAware {
    val tenant: String
}

@Entity
interface Book : TenantAware { ... }

@Component                                     // Spring Boot starter: picked up automatically
class TenantFilter(
    private val tenantProvider: TenantProvider
) : KFilter<TenantAware> {

    override fun filter(args: KFilterArgs<TenantAware>) {
        tenantProvider.tenant?.let {
            args.apply {
                where(table.tenant eq it)       // `table` is KNonNullProps<TenantAware>; orderBy(...) also allowed
            }
        }
    }
}

val sqlClient = newKSqlClient {                 // without Spring
    addFilters(TenantFilter(provider))
    addDisabledFilters(AdminOnlyFilter())       // registered but off until enabled per call
}
```

Imports: `org.babyfish.jimmer.sql.kt.filter.{KFilter, KFilterArgs}`, `org.babyfish.jimmer.sql.kt.ast.expression.eq`,
and the generated `com.example.model.common.tenant` extension.

Rules:

- A `@ManyToOne`/`@OneToOne` whose **target** is filtered must be nullable (`val store: BookStore?`), unless every
  filter on the target implements `KAssociationIntegrityAssuranceFilter` *and* also applies to the source entity.
- Fetcher-loaded associations are filtered too (`store.books` only contains the current tenant's books); with caches
  enabled, filtered associations need `KCacheableFilter` (multi-view cache) — see `references/cache.md`.
- `findById`/`findByIds` apply filters when no object cache is configured; with an object cache the root lookup
  ignores filters unless the filter implements `KShardingFilter`.

## Filter marker interfaces

| Interface (`org.babyfish.jimmer.sql.kt.filter`) | Purpose |
|---|---|
| `KFilter<E>` | basic filter |
| `KCacheableFilter<E>` | adds `getParameters(): SortedMap<String, Any>?` (cache sub-key) and `isAffectedBy(e: EntityEvent<*>): Boolean` (which changes invalidate filtered association caches) |
| `KAssociationIntegrityAssuranceFilter<E>` | promise that associated rows always share the filter value → allows non-null references to filtered targets |
| `KShardingFilter<E>` / `KShardingCacheableFilter<E>` | id-based lookups through the object cache still apply this filter |

```kotlin
class TenantFilter(private val tenantProvider: TenantProvider) : KCacheableFilter<TenantAware> {

    override fun filter(args: KFilterArgs<TenantAware>) {
        tenantProvider.tenant?.let { args.apply { where(table.tenant eq it) } }
    }

    override fun getParameters(): SortedMap<String, Any>? =
        tenantProvider.tenant?.let { sortedMapOf("tenant" to it) }

    override fun isAffectedBy(e: EntityEvent<*>): Boolean =
        e.isChanged(TenantAware::tenant)        // org.babyfish.jimmer.sql.kt.event.isChanged
}
```

## Disabling filters per call

`sqlClient.filters { ... }` returns a **new** temporary client; the original is unchanged:

```kotlin
val noTenant = sqlClient.filters { disableByTypes(TenantFilter::class) }
val admin    = sqlClient.filters { enable(adminOnlyFilter) }
val all      = sqlClient.filters { setBehavior(LogicalDeletedBehavior.IGNORED) }
```

`KFilterDsl` methods: `enable(...)`, `disable(...)`, `enableByTypes(KClass...)`, `disableByTypes(KClass...)`,
`setBehavior(LogicalDeletedBehavior)`, `setBehavior(KClass<*>, behavior)`, `setBehavior(ImmutableType, behavior)`.
Unlike Java's `FilterConfig` there is **no `disableAll()`** — list the filter types. Disabling user filters never
lifts logical deletion; that needs `setBehavior(...)`.

## Logical deletion at query time

Mapping (`@LogicalDeleted`) is in `references/entity-mapping.md`. Once mapped, Jimmer adds the "not deleted" predicate
to every query/fetch/statement and turns `deleteById`/`createDelete` into `UPDATE`s.

```kotlin
val everyone = sqlClient
    .filters { setBehavior(LogicalDeletedBehavior.IGNORED) }       // deleted + not deleted
    .createQuery(Author::class) { select(table) }
    .execute()

val recycleBin = sqlClient
    .filters { setBehavior(LogicalDeletedBehavior.REVERSED) }      // only deleted rows
    .createQuery(Author::class) { select(table) }
    .execute()

sqlClient.filters { setBehavior(Author::class, LogicalDeletedBehavior.IGNORED) }   // one type only
newKSqlClient { setLogicalDeletedBehavior(LogicalDeletedBehavior.DEFAULT) }         // global default

// restore a logically deleted row
sqlClient
    .filters { setBehavior(LogicalDeletedBehavior.IGNORED) }
    .executeUpdate(Author::class) {
        set(table.deleted, false)
        where(table.id eq 4L)
    }
```

Physical delete: `sqlClient.deleteById(Author::class, id, DeleteMode.PHYSICAL)`.

## Draft interceptors (audit fields, defaults)

A `DraftInterceptor<E, D>` (`org.babyfish.jimmer.sql.DraftInterceptor`, shared with Java) adjusts every object of type
`E` (entity or `@MappedSuperclass`) right before a save command writes it — inserts and updates, roots and nested
objects.

```kotlin
@MappedSuperclass
interface BaseEntity {
    val createdTime: LocalDateTime
    val modifiedTime: LocalDateTime
}

@Component
class BaseEntityDraftInterceptor : DraftInterceptor<BaseEntity, BaseEntityDraft> {

    override fun beforeSave(draft: BaseEntityDraft, original: BaseEntity?) {
        val now = LocalDateTime.now()
        if (!isLoaded(draft, BaseEntity::modifiedTime)) {          // org.babyfish.jimmer.kt.isLoaded
            draft.modifiedTime = now
        }
        if (original === null && !isLoaded(draft, BaseEntity::createdTime)) {
            draft.createdTime = now                                 // original == null → INSERT
        }
    }

    // properties of `original` to load besides id/key (columns only); generated XxxProps constants
    override fun dependencies(): Collection<TypedProp<BaseEntity, *>> =
        listOf(BaseEntityProps.CREATED_TIME)

    // skip objects that carry only their id and are treated as references
    override fun ignoreIdOnly(): Boolean = true
}

newKSqlClient { addDraftInterceptors(BaseEntityDraftInterceptor()) }   // without Spring
```

- `original` is `null` for an insert and the existing row (id + key + `dependencies()`) for an update.
- KSP generates a `XxxProps` object per entity/mapped superclass (`BaseEntityProps.CREATED_TIME`,
  `BookProps.NAME`, ...) with `TypedProp` constants — use them wherever an API wants a `TypedProp`.
- Never change `@Id` or `@Key` properties in `beforeSave`. 0.12.4 does not reliably reject it: for a key-matched
  object the UPDATE silently ignores the new key while the returned entity contains it.
- `ignoreIdOnly()` / `ignoreKeyOnly(group)` (default `false`): return `true` so that
  `save(Book { id = 1L }, SaveMode.UPDATE_ONLY)` is not touched (and not turned into an update of `modifiedTime`).
  Id-only *association targets* never reach interceptors.
- Override `beforeSaveAll(items: Collection<DraftInterceptor.Item<E, D>>)` to batch extra lookups (each item has
  `draft`, `original`, `state`); then `beforeSave` is only called if you delegate to it.
- Cost: an interceptor forces Jimmer to **select existing rows first** instead of a native upsert. If you don't need
  `original`, use a `DraftPreProcessor`.
- Interceptors only run inside save commands — not for `createUpdate`/`createInsert` statements.

## Draft pre-processors

`DraftPreProcessor<D>` runs before save without querying the database — cheaper, but it cannot know whether the row
exists:

```kotlin
@Component
class TenantPreProcessor(private val tenantProvider: TenantProvider) : DraftPreProcessor<TenantAwareDraft> {

    override fun beforeSave(draft: TenantAwareDraft) {
        if (!isLoaded(draft, TenantAware::tenant)) {
            draft.tenant = tenantProvider.tenant!!
        }
    }

    override fun ignoreIdOnly(): Boolean = true
}

newKSqlClient { addDraftPreProcessor(TenantPreProcessor(provider)) }
```

Check `isLoaded` before assigning or every update overwrites the column; "only on insert" logic needs a
`DraftInterceptor`.

## Triggers: listening to changes

| `TriggerType` | Meaning |
|---|---|
| `BINLOG_ONLY` (default) | events come from database CDC you feed into `sqlClient.binLog`; no SQL overhead; fired after commit |
| `TRANSACTION_ONLY` | Jimmer simulates triggers by selecting old rows during saves/deletes; fired inside the transaction |
| `BOTH` | `sqlClient.triggers` / `getTriggers(false)` = binlog, `getTriggers(true)` = transaction |

```kotlin
val sqlClient = newKSqlClient {
    setTriggerType(TriggerType.TRANSACTION_ONLY)              // Spring: jimmer.trigger-type=TRANSACTION_ONLY
}

sqlClient.triggers.addEntityListener(Book::class) { e ->      // e: EntityEvent<Book>
    // e.type: INSERT, UPDATE, DELETE, LOGICAL_DELETED, LOGICAL_INSERTED, EVICT
    val old: Book? = e.oldEntity                              // null for insert
    val new: Book? = e.newEntity                              // null for delete
    if (e.isChanged(Book::price)) {                           // org.babyfish.jimmer.sql.kt.event.isChanged
        val ref = e.getChangedRef(Book::price)!!              // org.babyfish.jimmer.sql.kt.event.getChangedRef
        println("${ref.oldValue} -> ${ref.newValue}")
    }
    val con = e.connection                                    // non-null only for transaction triggers
}

sqlClient.triggers.addAssociationListener(Book::authors) { e ->
    e.sourceId
    e.detachedTargetId                                        // non-null when a link is removed
    e.attachedTargetId                                        // non-null when a link is added
}
sqlClient.triggers.addEntityListener { e -> /* every entity type */ }
```

- Changing a FK (`Book.store`) also fires association events for both `Book::store` and the inverse `BookStore::books`.
- Transaction triggers run inside the transaction: an exception rolls it back; keep handlers fast. They require saves
  to select old rows (no native upsert) — use `TRANSACTION_ONLY` only when the DB has no CDC.
- Cache invalidation is driven by `getTriggers(false)`. With `TRANSACTION_ONLY`, the Spring Boot starter defers cache
  deletion until after commit (see `references/cache.md`).
- In `BOTH` mode a listener registered on both trigger objects runs twice; distinguish by `e.connection != null`.

## BinLog integration

Feed CDC messages (Maxwell, Debezium, Canal, ...) into Jimmer; it maps column names to properties, fires the binlog
triggers and invalidates caches:

```kotlin
@Component
class MaxwellListener(sqlClient: KSqlClient) {

    private val binLog = sqlClient.binLog
    private val reader = JsonCodec.jsonCodec().treeReader()   // org.babyfish.jimmer.jackson.codec.JsonCodec

    @KafkaListener(topics = ["maxwell"])
    fun onMessage(json: String, ack: Acknowledgment) {
        val node = reader.read(json)                          // org.babyfish.jimmer.jackson.codec.Node
        val table = node.get("table").castTo(String::class.java)
        when (node.get("type").castTo(String::class.java)) {
            "insert" -> binLog.accept(table, null, node.get("data"))
            "update" -> binLog.accept(table, node.get("old"), node.get("data"))
            "delete" -> binLog.accept(table, node.get("data"), null)
        }
        ack.acknowledge()
    }
}
```

`BinLog.accept(tableName, oldData: Node?, newData: Node?)` takes Jimmer's Jackson-neutral `Node`, not a Jackson
`JsonNode` (wrap one with `org.babyfish.jimmer.jackson.v2.NodeV2(jsonNode)`). Middle tables can be passed too (their
events become association events). Custom decoding: `setBinLogPropReader(prop | KClass) { prop, node -> value }` in
`newKSqlClient { }` / a `KCustomizer`.

**0.12.4 bug — register a `BigDecimal` reader.** The built-in `Node` implementations convert `BigDecimal`/`BigInteger`
values with `asInt()`, so any binlog row containing such a column fails with `IllegalArgumentException: Illegal json`
(cause: `Integer cannot be cast to BigDecimal`). Date/time columns are skipped unless you register a reader:

```kotlin
newKSqlClient {
    setBinLogPropReader(BigDecimal::class) { _, node -> BigDecimal(node.castTo(String::class.java)) }
    setBinLogPropReader(BigInteger::class) { _, node -> BigInteger(node.castTo(String::class.java)) }
}
```
