# Pitfalls, verified 0.12.4 behaviors, migration notes and review checklist (Java)

Everything here was reproduced against Jimmer 0.12.4. Use it when a compile error, runtime exception or surprising
result shows up, and as a checklist when reviewing Jimmer code.

## Contents
- [Compile-time problems](#compile-time-problems)
- [Runtime exceptions](#runtime-exceptions)
- [Silent wrong results (the dangerous ones)](#silent-wrong-results-the-dangerous-ones)
- [APIs that changed or never existed (docs/blogs vs 0.12.4)](#apis-that-changed-or-never-existed-docsblogs-vs-0124)
- [Coming from JPA/Hibernate/MyBatis](#coming-from-jpahibernatemybatis)
- [Review checklist](#review-checklist)

## Compile-time problems

| Symptom | Cause / fix |
|---|---|
| `cannot find symbol BookTable/BookDraft/BookFetcher/BookView` | code not generated yet or stale: recompile; check `annotationProcessor "org.babyfish.jimmer:jimmer-apt"` (Gradle) / `annotationProcessorPaths` (Maven) |
| `Immutables`/`Fetchers`/`Tables` moved package | they are generated in the **common parent package** of all entities in the module; adding an entity in another package moves them — update imports |
| DTO class not regenerated after editing only a `.dto` file | Gradle doesn't track `src/main/dto` as an APT input; add `tasks.named('compileJava') { inputs.files(fileTree('src/main/dto') { include '**/*.dto' }).withPathSensitivity(PathSensitivity.RELATIVE) }` or run `clean` |
| Module with DTOs/controllers but no entities generates nothing | add `@EnableDtoGeneration` (Java) to any class of that module; it still needs `jimmer-apt` |
| `@TypedTuple` on a `record` rejected | use a class with an all-args constructor and getters |
| `no suitable method found for orderBy(Str, Order)` | don't mix `Expression`s and `Order`s in one `orderBy` call: `orderBy(t.name().asc(), t.edition().desc())` |
| `avg()` result type mismatch | `avg()` → `Double`; use `avgAsDecimal()` for `BigDecimal` |
| `select(...)` with more than 9 selections | declare a `@TypedTuple` class and select its generated `XxxMapper` |
| `p.not()` does not exist | `Predicate.not(p)` is static |
| `inIf((Collection) null)` ambiguous | cast the null (`(Collection<String>) null`) or pass a typed variable |
| `@Formula` cannot be resolved | import `org.babyfish.jimmer.Formula` (not `org.babyfish.jimmer.sql.Formula`) |
| two `@LogicalDeleted` props | only one per entity |
| non-null `@ManyToOne` to a filtered entity rejected | make it `@Nullable`, or have all target filters implement `AssociationIntegrityAssuranceFilter` and also apply to the source |
| `ImmutableModule` not found | 0.12 uses `org.babyfish.jimmer.jackson.v2.ImmutableModuleV2` / `...v3.ImmutableModuleV3` |

## Runtime exceptions

| Exception / message | Cause / fix |
|---|---|
| `UnloadedException: property X is unloaded` | you read a property that the fetcher/DTO didn't load. Fetch it, or check `ImmutableObjects.isLoaded(obj, XProps.X)` |
| `UnloadedException` while fetching an ordered association **with an association cache** | Jimmer sorts cached children in memory by `orderedProps`; include those props in the child fetcher (even `books()` id-only fails) |
| `SaveException.NeitherIdNorKey` | object has neither id nor a complete `@Key` group (e.g. a root `TreeNode` without `parent` set — set `parent(null)` explicitly) |
| `SaveException.NotUnique` | unique constraint violated (translated). Use an `ExceptionTranslator` for a business error |
| `SaveException.TargetIsNotTransferable` | saving a parent whose child belongs to another parent; allow with `setTargetTransferMode(...)`/`setTargetTransferable(true)` |
| `SaveException.CannotDissociateTarget` | `REPLACE`/delete would orphan children whose many-to-one is `CHECK`/`NONE`; configure `@OnDissociate(SET_NULL/DELETE)` or `setDissociateAction` |
| `SaveException.OptimisticLockError` | stale `@Version` or custom optimistic lock predicate false |
| `IllegalArgumentException: Illegal exception translator type ... is not class type` | an `ExceptionTranslator` written as a lambda; use an anonymous class or a named class |
| `IllegalStateException` from `sqlClient.transaction(...)` | needs a `TxConnectionManager` (e.g. `simpleConnectionManager`); under the Spring starter it needs a `DataSourceTransactionManager` bean — or just use `@Transactional` |
| `NullPointerException: The parameters[i] ... cannot be null ... @DynamicParam` | Spring Data `JRepository` derived-query parameters are only dynamic with `@DynamicParam` |
| `UnsupportedOperationException` in `createInsert(associationTable, source)` | middle-table insert-from-select only supports a rootless source (`createBaseQuery()` with constants) in 0.12.4 |
| exception for `typeMatchMode(TypeMatchMode.EXACT)` on an abstract type | `EXACT` needs a concrete type |
| "NestMember requires ASM7" when using `weakJoin(lambda)` | the lambda's enclosing class has nested classes; declare the weak join as a top-level `WeakJoin` class (`weakJoin(BookAuthorJoin.class)`) |
| `NoClassDefFoundError: org/antlr/...` with `FetcherCompiler` | add `org.antlr:antlr4-runtime:4.13.0` |
| Update with join fails | only the MySQL-style dialects, `PostgresDialect` and `SQLiteDialect` support joins in `createUpdate`; use a subquery |
| `returning(...)` on update/insert unsupported | DML returning is implemented for `H2Dialect` and `PostgresDialect` |
| error creating table `JIMMER_TRANS_CACHE_OPERATOR` | a `TransactionCacheOperator` needs a dialect that can create it (`H2Dialect`, MySQL-style, `OracleDialect`, `PostgresDialect`); create the table yourself otherwise |
| `IllegalArgumentException: Illegal json` from `getBinLog().accept(...)` (cause `Integer cannot be cast to BigDecimal`) | 0.12.4 converts `BigDecimal`/`BigInteger` binlog values with `asInt()`; register `setBinLogPropReader(BigDecimal.class, (p, n) -> new BigDecimal(n.castTo(String.class)))` |

## Silent wrong results (the dangerous ones)

1. **Constant on the left of a comparison is rendered with the negated operator.** `Expression.value(x).gt(col)`
   becomes `col <= x` (`<` → `>=`, `>` → `<=`, `>=` → `<`, `<=` → `>`). Always write the column first:
   `col.lt(x)`. This also hits `setUpdateWhere`/`setOptimisticLock` when Jimmer evaluates them with a pre-query
   (interceptors, `TRANSACTION_ONLY` triggers, no native upsert): `values.newNumber(P).gt(table.p())` reports equal
   values as accepted — write `table.p().lt(values.newNumber(P))`.
2. **`AssociatedSaveMode.REPLACE` is the default.** Saving `Book{id, authors:[1]}` removes all other author links;
   saving a store with a partial `books` list dissociates the missing books. Use `MERGE`/`APPEND` for additive edits.
3. **`null` vs unloaded.** `Immutables.createBook(d -> d.setId(1L).setStore(null))` clears the FK; forgetting a
   property leaves it untouched. Static input DTOs set `null` for missing JSON fields — use `dynamic`/`fuzzy` inputs
   for PATCH semantics.
4. **`filters(cfg -> cfg.disableAll())` keeps the logical-deletion filter.** Use
   `cfg.setBehavior(LogicalDeletedBehavior.IGNORED)` (or `REVERSED`).
5. **Object cache vs filters.** With an object cache, `findById` returns a cached object even if a global filter
   (tenant, logical deletion) would hide it; misses are loaded with filters. Query instead when filters matter.
6. **`BINLOG_ONLY` (default) + caches without CDC wiring = caches are never invalidated.**
7. **Changing a `@Key` in a `DraftInterceptor` is not rejected** for key-matched objects: the UPDATE ignores it while
   the returned entity shows the new value.
8. **Dissociated children with `@LogicalDeleted` are physically deleted** by default when the dissociate action is
   `DELETE` and the FK is real (`setDissociationLogicalDeleteEnabled(true)` to delete logically).
9. **`createDelete(...).execute()` counts rows of all touched tables** (middle-table rows included).
10. **`isModified()` on a save result is a reference comparison**, not "the row changed"; use `isAccepted()` and
    affected-row counts.
11. **Java `@Formula` default methods** report `isLoaded() == true` whenever their dependencies are loaded, but are
    only serialized when fetched explicitly.
12. **Repository shortcuts are deprecated and easy to misread**: `insert` = `INSERT_ONLY` + `APPEND` (never updates);
    the non-deprecated `save(entity, SaveMode)` keeps `REPLACE` for associations.
13. **`inIf(emptyList)` is not ignored** — it matches no rows; only `inIf(null)` is skipped.

## APIs that changed or never existed (docs/blogs vs 0.12.4)

| You may see | Use in 0.12.4 |
|---|---|
| `sqlClient.insert/update/merge/insertIfAbsent(entity)` | `save(entity, SaveMode.X, AssociatedSaveMode.Y)` or `saveCommand(entity).setMode(...)` (the repository shortcuts are `@Deprecated`) |
| `sqlClient.save(entity, SaveMode.X)` / `save(entity, AssociatedSaveMode.Y)` | both modes are required in the overload, or use `saveCommand` |
| `sqlClient.insertEntities/updateEntities/saveEntities(list, mode)` | `saveEntities(list, SaveMode, AssociatedSaveMode)` / `saveEntitiesCommand(list)` |
| `UserOptimisticLock` | `UpdateCondition<E, T>` (same lambda shape) |
| `deleteCommand(...).configure(it -> ...)` | call `setDissociateAction(...)`, `setMode(...)` directly on the command |
| `getAssociations(...).saveAllCommand(...).checkExistence()` | `.ignoreConflict(true)`; or `insertIfAbsent`/`insertAllIfAbsent` |
| `ImmutableModule` | `ImmutableModuleV2` / `ImmutableModuleV3` (or `JsonCodec`) |
| `RedisValueBinder...objectMapper(objectMapper)` | `new RedisCacheCreator(connectionFactory, JsonCodec.jsonCodec())` + `AbstractCacheFactory` |
| `BinLog.accept(table, JsonNode, JsonNode)` | `accept(table, Node, Node)` with `org.babyfish.jimmer.jackson.codec.Node` (`JsonCodec.jsonCodec().treeReader().read(json)`) |
| `@org.babyfish.jimmer.client.Api` | `@org.babyfish.jimmer.client.meta.Api` (or `@EnableImplicitApi`) |
| `DataFetchingEnvironments.createQuery(Book.class, env)` (GraphQL) | `DataFetchingEnvironments.createFetcher(Book.class, env)` |
| `@ThrowsAll` | Java `throws XxxException` |
| `jimmer.save-command-pessimistic-lock`, `jimmer.client.ts.anonymous` | don't exist; per command `setPessimisticLock(...)` |
| `findNullable(id, fetcher)` | exists on Spring Data `JRepository`; class repositories (`JavaRepository`/`AbstractJavaRepository`) use `findById(id, fetcher)` (returns `null` when absent) |
| `JRepository` derived query with only `@Nullable` params | add `@DynamicParam` |
| `org.babyfish.jimmer.spring.SpringClients` | `org.babyfish.jimmer.spring.SqlClients` |

## Coming from JPA/Hibernate/MyBatis

- No persistence context, dirty checking, lazy loading or proxies: load what you need with fetchers, write with
  save commands. An entity you got from a query is an immutable snapshot.
- No `cascade` or owning-side rules: the shape of the object you pass decides what is saved; either side of a
  bidirectional association can be saved.
- `@OneToMany` always needs `mappedBy` (mirror of a `@ManyToOne` or many-to-many).
- Ids: `@GeneratedValue(strategy = IDENTITY | SEQUENCE)`, `UUIDIdGenerator`, or a custom `UserIdGenerator`.
- Write SQL-like code in the typed DSL; for reports, inject native SQL fragments (`Expression.sql(...)`,
  `Predicate.sql(...)`) or use `JdbcTemplate` in the same transaction.
- Optional filters do not need `if` chains: `eqIf`, `likeIf`, `betweenIf`, `whereIf`, specifications.

## Review checklist

Entities and mapping
- [ ] Nullity matches the schema (Java: non-primitive = non-null unless `@Nullable`); references to filtered/remote/fake-FK targets are `@Nullable`.
- [ ] Every entity that is saved without ids has a `@Key`, and `@KeyUniqueConstraint` only when a matching unique constraint exists.
- [ ] `@OnDissociate` set on many-to-one props whose parents get replaced or deleted.
- [ ] One `@LogicalDeleted` at most; queries that must see deleted rows use `LogicalDeletedBehavior`.

Queries
- [ ] Optional parameters use `*If` predicates; static predicates don't receive `null` unintentionally.
- [ ] Comparisons put the column on the left.
- [ ] Collection associations are filtered with implicit subqueries/`asTableEx()` + `distinct()`, not top-level joins.
- [ ] Result shapes come from fetchers/DTOs; reusable fetchers are `static final` constants.
- [ ] Paging uses `fetchPage`/`fetchSlice` with a deterministic `orderBy`.

Saves and deletes
- [ ] The intended `SaveMode`/`AssociatedSaveMode` is explicit wherever the default (`UPSERT`/`REPLACE`) is not wanted.
- [ ] Partial updates send only the changed properties (or use `dynamic` input DTOs).
- [ ] Multi-statement mutations run in a transaction (`@Transactional`).
- [ ] Unique-constraint errors are translated (`ExceptionTranslator`) instead of check-then-insert.
- [ ] `setUpdateWhere`/`setOptimisticLock` predicates have the column on the left; callers check `isAccepted()`.

Infrastructure
- [ ] DraftInterceptors only fill unloaded props, never touch id/key, and set `ignoreIdOnly()` when appropriate.
- [ ] With caches: a trigger path is wired (CDC for `BINLOG_ONLY`, or `TRANSACTION_ONLY`), transient resolvers implement `getAffectedSourceIds`, ordered association fetchers include the order props.
- [ ] REST endpoints returning entities carry `@FetchBy` matching the fetcher actually used; errors declared with `throws`.
