# Global filters, logical deletion, draft interceptors and triggers (Java)

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

A global filter adds a `where` condition to **every** query of the entities it applies to: root queries, fetcher-loaded
associations, implicit subqueries, `findAll`, update/delete statements. Typical uses: multi-tenancy, data permissions.

Filter an abstract `@MappedSuperclass` so one filter covers many entities (the generic argument is the generated
`XxxProps` interface of the superclass):

```java
@MappedSuperclass
public interface TenantAware {
    String tenant();
}

@Entity
public interface Book extends TenantAware { ... }

@Component                                     // with the Spring Boot starter: picked up automatically
public class TenantFilter implements Filter<TenantAwareProps> {

    private final TenantProvider tenantProvider;

    public TenantFilter(TenantProvider tenantProvider) { this.tenantProvider = tenantProvider; }

    @Override
    public void filter(FilterArgs<TenantAwareProps> args) {
        String tenant = tenantProvider.get();
        if (tenant != null) {
            args.where(args.getTable().tenant().eq(tenant));
        }
        // args.orderBy(...) and args.createSubQuery(...) are also available
    }
}

JSqlClient sqlClient = JSqlClient.newBuilder()
        .addFilters(new TenantFilter(provider))          // without Spring
        .addDisabledFilters(new AdminOnlyFilter())       // registered but off until enabled per call
        .build();
```

Rules:

- A `@ManyToOne`/`@OneToOne` whose **target** is filtered must be `@Nullable` (the filter may hide the target), unless
  every filter on the target implements `AssociationIntegrityAssuranceFilter` *and* also applies to the source entity.
- Fetcher-loaded associations are filtered too; with caches enabled, filtered associations need `CacheableFilter`
  (multi-view cache) — see `references/cache.md`.
- `findById`/`findByIds` apply filters when no object cache is configured; with an object cache the root lookup
  ignores filters unless the filter implements `ShardingFilter`.

## Filter marker interfaces

| Interface (`org.babyfish.jimmer.sql.filter`) | Purpose |
|---|---|
| `Filter<P>` | basic filter |
| `CacheableFilter<P>` | adds `SortedMap<String, Object> getParameters()` (cache sub-key, e.g. `{"tenant": "a"}`) and `boolean isAffectedBy(EntityEvent<?> e)` (which changes invalidate the filtered association caches) |
| `AssociationIntegrityAssuranceFilter<P>` | promise that associated rows always share the filter value → allows non-null references to filtered targets |
| `ShardingFilter<P>` | make id-based lookups through the cache still apply this filter (sharding key) |

```java
public class TenantFilter implements CacheableFilter<TenantAwareProps> {
    @Override public void filter(FilterArgs<TenantAwareProps> args) { ... }
    @Override public SortedMap<String, Object> getParameters() {
        SortedMap<String, Object> map = new TreeMap<>();
        map.put("tenant", tenantProvider.get());
        return map;
    }
    @Override public boolean isAffectedBy(EntityEvent<?> e) {
        return e.isChanged(TenantAwareProps.TENANT);
    }
}
```

## Disabling filters per call

`sqlClient.filters(cfg -> ...)` returns a **new** temporary client; the original is unchanged:

```java
JSqlClient noTenant = sqlClient.filters(cfg -> cfg.disableByTypes(TenantFilter.class));
JSqlClient admin    = sqlClient.filters(cfg -> cfg.enable(adminOnlyFilter));
JSqlClient noUser   = sqlClient.filters(cfg -> cfg.disableAll());     // user filters only!
```

`FilterConfig` methods: `enable(...)`, `disable(...)`, `enableByTypes(...)`, `disableByTypes(...)`, `disableAll()`,
`setBehavior(LogicalDeletedBehavior)` (all types), `setBehavior(Class|ImmutableType, behavior)`,
`setBehavior(associationProp, behavior)`. **`disableAll()` does not lift the logical-deletion filter** — use
`setBehavior(LogicalDeletedBehavior.IGNORED)` for that.

## Logical deletion at query time

Mapping (`@LogicalDeleted`) is in `references/entity-mapping.md`. Once mapped, Jimmer adds the "not deleted" predicate
to every query/fetch/statement and turns `deleteById`/`createDelete` into `UPDATE`s.

```java
List<Author> all = sqlClient
        .filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))   // deleted + not deleted
        .createQuery(author).select(author).execute();

List<Author> recycleBin = sqlClient
        .filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.REVERSED))  // only deleted rows
        .createQuery(author).select(author).execute();

sqlClient.filters(cfg -> cfg.setBehavior(Author.class, LogicalDeletedBehavior.IGNORED));   // one type only
JSqlClient.newBuilder().setLogicalDeletedBehavior(LogicalDeletedBehavior.DEFAULT);          // global default
```

To restore a logically deleted row: `sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))
.createUpdate(author).set(author.deleted(), false).where(author.id().eq(id)).execute()`. Physical delete:
`deleteById(type, id, DeleteMode.PHYSICAL)`.

## Draft interceptors (audit fields, defaults)

A `DraftInterceptor<E, D>` adjusts every object of type `E` (entity or `@MappedSuperclass`) right before a save
command writes it — inserts and updates, roots and nested objects.

```java
@MappedSuperclass
public interface BaseEntity {
    LocalDateTime createdTime();
    LocalDateTime modifiedTime();
}

@Component
public class BaseEntityDraftInterceptor implements DraftInterceptor<BaseEntity, BaseEntityDraft> {

    @Override
    public void beforeSave(@NotNull BaseEntityDraft draft, @Nullable BaseEntity original) {
        LocalDateTime now = LocalDateTime.now();
        if (!ImmutableObjects.isLoaded(draft, BaseEntityProps.MODIFIED_TIME)) {
            draft.setModifiedTime(now);
        }
        if (original == null && !ImmutableObjects.isLoaded(draft, BaseEntityProps.CREATED_TIME)) {
            draft.setCreatedTime(now);                 // original == null → INSERT
        }
    }

    // properties of `original` to load besides id/key (columns only); called once, must be stable
    @Override
    public Collection<TypedProp<BaseEntity, ?>> dependencies() {
        return List.of(BaseEntityProps.CREATED_TIME);
    }

    // don't touch objects that carry only their id and are treated as references
    @Override
    public boolean ignoreIdOnly() { return true; }
}

JSqlClient.newBuilder().addDraftInterceptors(new BaseEntityDraftInterceptor());   // without Spring
```

- `original` is `null` for an insert and the existing row (id + key + `dependencies()`) for an update.
- Never change `@Id` or `@Key` properties in `beforeSave`. 0.12.4 does not reliably reject it: for a key-matched
  object the UPDATE silently ignores the new key while the returned entity contains it.
- `ignoreIdOnly()` / `ignoreKeyOnly(group)` (default `false`): return `true` to skip objects that carry only their id
  (or key) and are treated as references — e.g. `save(Immutables.createBook(d -> d.setId(1L)))` would otherwise get
  `modifiedTime` set and be updated. Id-only *association targets* never reach interceptors.
- Override `beforeSaveAll(Collection<DraftInterceptor.Item<E, D>> items)` to batch extra lookups (each item has
  `getDraft()`, `getOriginal()`, `getState()`); then `beforeSave` is only called if you delegate to it.
- Cost: an interceptor forces Jimmer to **select existing rows first** (`QueryReason.INTERCEPTOR`) instead of a native
  upsert. If you don't need `original`, use a `DraftPreProcessor`.
- Interceptors only run inside save commands — not for `createUpdate`/`createInsert` statements.
- Kotlin interceptors implement the same interface (`DraftInterceptor<BaseEntity, BaseEntityDraft>`).

## Draft pre-processors

`DraftPreProcessor<D>` runs unconditionally before save without querying the database — cheaper, but it cannot know
whether the row exists:

```java
@Component
public class TenantPreProcessor implements DraftPreProcessor<TenantAwareDraft> {
    @Override
    public void beforeSave(@NotNull TenantAwareDraft draft) {
        if (!ImmutableObjects.isLoaded(draft, TenantAwareProps.TENANT)) {
            draft.setTenant(TenantContext.current());
        }
    }
    @Override public boolean ignoreIdOnly() { return true; }
}

JSqlClient.newBuilder().addDraftPreProcessor(new TenantPreProcessor());
```

Note that a pre-processor setting a value on *every* save also overwrites that column on updates unless you check
`isLoaded` (as above) — and since it can't see the DB row, "only on insert" logic needs a `DraftInterceptor`.

## Triggers: listening to changes

| `TriggerType` | Meaning |
|---|---|
| `BINLOG_ONLY` (default) | events come from database CDC you feed into `sqlClient.getBinLog()`; no SQL overhead; fired after commit |
| `TRANSACTION_ONLY` | Jimmer simulates triggers by selecting old rows during saves/deletes; fired inside the transaction |
| `BOTH` | `getTriggers()`/`getTriggers(false)` = binlog, `getTriggers(true)` = transaction |

```java
JSqlClient sqlClient = JSqlClient.newBuilder()
        .setTriggerType(TriggerType.TRANSACTION_ONLY)          // Spring: jimmer.trigger-type=TRANSACTION_ONLY
        .build();

sqlClient.getTriggers().addEntityListener(Book.class, e -> {
    // e.getType(): INSERT, UPDATE, DELETE, LOGICAL_DELETED, LOGICAL_INSERTED, EVICT
    Book oldBook = e.getOldEntity();                          // null for insert
    Book newBook = e.getNewEntity();                          // null for delete
    if (e.isChanged(BookProps.PRICE)) {
        ChangedRef<BigDecimal> price = e.getChangedRef(BookProps.PRICE);
        System.out.println(price.getOldValue() + " -> " + price.getNewValue());
    }
    Connection con = e.getConnection();                       // non-null only for transaction triggers
});

sqlClient.getTriggers().addAssociationListener(BookProps.AUTHORS, e -> {
    Object bookId = e.getSourceId();
    Object detached = e.getDetachedTargetId();                // non-null when a link is removed
    Object attached = e.getAttachedTargetId();                // non-null when a link is added
});
sqlClient.getTriggers().addEntityListener(e -> { /* every entity type */ });
```

- Changing a FK (`Book.store`) also fires association events for both `Book.store` and the inverse `BookStore.books`.
- Transaction triggers run inside the transaction: an exception rolls it back; keep handlers fast. They require
  saves to select old rows (disables native upsert) — use `TRANSACTION_ONLY` only when the DB has no CDC.
- Jimmer's cache invalidation is driven by `getTriggers(false)`. With `TRANSACTION_ONLY`, the Spring Boot starter
  defers cache deletion until after commit (see `references/cache.md`).
- In `BOTH` mode the same listener registered on both trigger objects runs twice; distinguish by
  `e.getConnection() != null` (transaction) vs `null` (binlog).

## BinLog integration

Feed CDC messages (Maxwell, Debezium, Canal, …) into Jimmer; it maps column names to properties, fires the binlog
triggers and invalidates caches:

```java
@Component
public class MaxwellListener {

    private final BinLog binLog;

    public MaxwellListener(JSqlClient sqlClient) { this.binLog = sqlClient.getBinLog(); }

    @KafkaListener(topics = "maxwell")
    public void onMessage(String json, Acknowledgment ack) throws Exception {
        JsonReader<Node> reader = JsonCodec.jsonCodec().treeReader();
        Node node = reader.read(json);
        String table = node.get("table").castTo(String.class);
        switch (node.get("type").castTo(String.class)) {
            case "insert" -> binLog.accept(table, null, node.get("data"));
            case "update" -> binLog.accept(table, node.get("old"), node.get("data"));
            case "delete" -> binLog.accept(table, node.get("data"), null);
        }
        ack.acknowledge();
    }
}
```

`BinLog.accept(String tableName, Node oldData, Node newData)` takes `org.babyfish.jimmer.jackson.codec.Node` (Jimmer's
Jackson-2/3 neutral tree) — not a Jackson `JsonNode` in 0.12. Build it with `JsonCodec.jsonCodec().treeReader().read(...)`
or wrap an existing Jackson 2 node with `new org.babyfish.jimmer.jackson.v2.NodeV2(jsonNode)`. Values the CDC tool
encodes unusually (Debezium decimals/timestamps) are decoded with
`JSqlClient.Builder.setBinLogPropReader(Class<?> | prop, (prop, node) -> value)`. Middle tables can be passed too
(their events become association events).

**0.12.4 bug — register a `BigDecimal` reader.** The built-in `Node` implementations convert `BigDecimal`/`BigInteger`
values with `asInt()`, so any binlog row containing such a column fails with `IllegalArgumentException: Illegal json`
(cause: `Integer cannot be cast to BigDecimal`). Date/time columns are skipped (not applied) unless you register a
reader for them as well:

```java
JSqlClient.newBuilder()
        .setBinLogPropReader(BigDecimal.class, (prop, node) -> new BigDecimal(node.castTo(String.class)))
        .setBinLogPropReader(BigInteger.class, (prop, node) -> new BigInteger(node.castTo(String.class)))
        ...
```

With the Spring Boot starter, apply them in a `JSqlClient.Builder` customizer bean (`Customizer`).
