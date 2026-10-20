# Pitfalls, verified 0.12.4 behaviors, migration notes and review checklist (Kotlin)

Everything here was reproduced against Jimmer 0.12.4 (Kotlin 2.1.20, KSP 2.1.20-2.0.1). Use it when a compile
error, runtime exception or surprising result shows up, and as a checklist when reviewing Jimmer Kotlin code.

## Contents
- [Compile-time problems](#compile-time-problems)
- [Runtime exceptions](#runtime-exceptions)
- [Silent wrong results (the dangerous ones)](#silent-wrong-results-the-dangerous-ones)
- [APIs that changed or never existed (docs/blogs vs 0.12.4)](#apis-that-changed-or-never-existed-docsblogs-vs-0124)
- [Coming from JPA/Hibernate/Exposed](#coming-from-jpahibernateexposed)
- [Review checklist](#review-checklist)

## Compile-time problems

| Symptom | Cause / fix |
|---|---|
| `Unresolved reference 'BookDraft'`, `fetchBy`, `table.name`, `BookView` | code not generated or stale: run `./gradlew kspKotlin`/build; check `ksp("org.babyfish.jimmer:jimmer-ksp:…")` in **this** module and `kotlin.srcDir("build/generated/ksp/main/kotlin")` for the IDE |
| KSP `GeneratorException ... declares several types decorated by ...` | **one Jimmer type per `.kt` file**: a file may declare only one `@Entity`/`@MappedSuperclass`/`@Embeddable`/`@Immutable` type |
| DTO class not regenerated after editing only a `.dto` file | KSP doesn't track `src/main/dto`; add the `KspAATask` inputs block from `references/setup-and-config.md`, or clean |
| KSP plugin fails to apply / "ksp-… is too old for kotlin-…" | the KSP version prefix must equal the Kotlin version (`2.1.20-2.0.1` for Kotlin 2.1.20) |
| Generated extension properties like `table.name` not found | import them: they are top-level extensions in the entity's package (`com.example.model.name`); IDE auto-import usually handles it |
| `Unresolved reference 'not'` for `not(p)` | it is an extension: `p.not()` |
| `'val' cannot be reassigned` inside `Book { name = … }` | a local variable/parameter with the same name (`name`, `price`, …) shadows the draft property; write `this.name = …` or rename the local |
| Type mismatch `Long?` vs `Long` in `select(...)`/tuples | nullable expressions (`table.storeId`, `max(...)`, `avgAsDecimal(...)`, outer-joined props) need `.asNonNull()` for non-null slots |
| `avg()` result is `Double` | `avgAsDecimal(...)` for `BigDecimal` |
| `Cannot access 'source'` on an `Association` row (association-table query result) | use the functions `source()` / `target()` (or `sourceId`/`targetId`) |
| `Not enough information to infer type variable K` in `creator.createForObject(type)` | pass type arguments: `createForObject<Any, Any>(type)`, `createForProp<Any, List<*>>(prop, multiView)` |
| `None of the following candidates is applicable: save(...)` on a `KotlinRepository` | no `save(entity, associatedMode)` overload; use `save(entity, SaveMode.X, AssociatedSaveMode.Y)` |
| `disableAll()` unresolved inside `sqlClient.filters { }` | `KFilterDsl` has no `disableAll()`; `disableByTypes(...)` / `setBehavior(...)` |
| `Unresolved reference 'PageParam' / 'SaveException' / 'SortUtils'` | `org.babyfish.jimmer.spring.repo.PageParam`, `org.babyfish.jimmer.sql.exception.SaveException`, `org.babyfish.jimmer.spring.model.SortUtils` |
| `@Formula(dependencies = …)` property rejected in a `where`/`select` | Kotlin-getter formulas are fetcher-only; use `@Formula(sql = "…%alias…")` for the DSL |
| `ImmutableModule` not found | 0.12: `org.babyfish.jimmer.jackson.v2.ImmutableModuleV2` / `…v3.ImmutableModuleV3` |

## Runtime exceptions

| Exception / message | Cause / fix |
|---|---|
| `UnloadedException: property X is unloaded` | the fetcher/DTO didn't load it. Fetch it, or check `isLoaded(obj, Book::store)` (`org.babyfish.jimmer.kt.isLoaded`) |
| `UnloadedException` fetching an ordered association **with an association cache** | include the `orderedProps` in the child fetcher (`books { name(); edition() }`); id-only `books()` fails |
| `SaveException.NeitherIdNorKey` | no id and incomplete `@Key`; a root `TreeNode` keyed by `(name, parent)` must set `parent = null` explicitly |
| `SaveException.NotUnique` / `CannotDissociateTarget` / `TargetIsNotTransferable` / `OptimisticLockError` | see `references/save-command.md`; translate business errors with an `ExceptionTranslator` |
| `IllegalArgumentException: Illegal exception translator type ... is not class type` | an `ExceptionTranslator` written as a SAM lambda loses its generic type; use `object : ExceptionTranslator<SaveException.NotUnique> { … }` or a named class |
| `NullPointerException: The parameters[i] ... cannot be null ... @DynamicParam` | `KRepository` derived-query params are dynamic only with `@DynamicParam` — a Kotlin `String?` is not enough |
| `No qualifying bean of type 'KSqlClient'` (Spring) | set `jimmer.language: kotlin`; the default creates a `JSqlClient` |
| `UnsupportedOperationException` in `createInsert(Book::authors, source)` | middle-table insert-from-select needs a rootless source (`createBaseQuery { select(...constants) }`) |
| `IllegalArgumentException: Illegal json` from `binLog.accept(...)` (cause `Integer cannot be cast to BigDecimal`) | 0.12.4 reads `BigDecimal`/`BigInteger` binlog values with `asInt()`; register `setBinLogPropReader(BigDecimal::class) { _, n -> BigDecimal(n.castTo(String::class.java)) }` |
| `IllegalStateException` from `sqlClient.transaction { }` | needs a `TxConnectionManager` (e.g. `simpleConnectionManager`); under the Spring starter it needs a `DataSourceTransactionManager` bean — or just use `@Transactional` |
| Update with join fails | joins in `createUpdate` only for the MySQL-style dialects, `PostgresDialect` and `SQLiteDialect`; use a subquery |
| `returning(...)` unsupported | DML returning: `H2Dialect`, `PostgresDialect` |
| `NoClassDefFoundError: org/antlr/...` with `FetcherCompiler` | add `org.antlr:antlr4-runtime:4.13.0` |

## Silent wrong results (the dangerous ones)

1. **Constant on the left of a comparison is rendered with the negated operator.** `value(x) gt table.price` becomes
   `PRICE <= x` (`<` → `>=`, `>` → `<=`, ...). Always write the column first (`table.price lt x`). Save-command
   predicates built with `newNonNull(...)` were not affected in tests, but keep the column first there too.
2. **Association-mode defaults differ by overload.** `save(e)` → `UPSERT` + `REPLACE`; `save(e, SaveMode.X)` →
   **`MERGE`**; `saveCommand(e, SaveMode.X)` → **`REPLACE`**; `KotlinRepository.save(e, mode)` → **`REPLACE`**.
   Pass the association mode explicitly whenever associations are loaded.
3. **`REPLACE` removes what you didn't send.** Saving `Book { id = 1L; authorIds = listOf(1L) }` with `REPLACE`
   unlinks all other authors; a store saved with a partial `books` list dissociates the others.
4. **`null` vs unloaded.** In `Book { … }`, properties you don't assign are unloaded (untouched by saves); assigning
   `null` writes NULL. In `dynamic` input DTOs a `null` for a **non-null** entity property always means "unloaded",
   and for nullable props the `isXxxLoaded` flag decides (pass `isStoreIdLoaded = true` to write NULL from code).
   Static inputs write `null` for missing JSON fields.
5. **`` `valueIn?` `` with an empty collection is not ignored** — it renders `1 = 0` (no rows); only `null` is ignored.
6. **Reference paths are inner joins, even inside `or(...)`.** `table.store.name eq "X"` drops books without a store;
   use `` table.`store?`.name ``.
7. **In Kotlin mode, Java `Filter`/`Customizer`/`Initializer` beans are ignored** (warning in the log only).
8. **Object cache vs filters.** A cached `findById` hit is returned without re-checking global filters/logical
   deletion. Query when filter semantics matter.
9. **`BINLOG_ONLY` (default) + caches without CDC wiring = caches are never invalidated.**
10. **`sqlClient.filters { disableByTypes(...) }` never lifts logical deletion** — use
    `setBehavior(LogicalDeletedBehavior.IGNORED/REVERSED)`.
11. **Changing a `@Key` in a `DraftInterceptor` is not rejected** for key-matched objects: the UPDATE ignores it while
    the returned entity shows the new value.
12. **Dissociated `@LogicalDeleted` children are physically deleted** by default (`setDissociationLogicalDeleteEnabled(true)`).
13. **`createDelete { }.execute()` counts rows of all touched tables** (middle-table rows included).
14. **`isModified` on a save result is a reference comparison**; use `isAccepted` and affected-row counts.
15. **Objects read back from JSON are not necessarily `==`** to the originals (equality includes load state).

## APIs that changed or never existed (docs/blogs vs 0.12.4)

| You may see | Use in 0.12.4 |
|---|---|
| `sqlClient.insert/update/merge(entity)` | `save(entity, SaveMode.X, AssociatedSaveMode.Y)` or `saveCommand(entity) { setMode(...) }` (repository `insert`/`update`/`insertIfAbsent`/`merge` are `@Deprecated`) |
| `sqlClient.entities.save(entity) { setAutoAttachingAll() }` | `setAssociatedModeAll(AssociatedSaveMode.APPEND/MERGE)` |
| `KFilterDsl.disableAll()` | `disableByTypes(...)` |
| `ExceptionTranslator { e, args -> … }` lambda | `object : ExceptionTranslator<T> { override fun translate(...) }` |
| `ImmutableModule` | `ImmutableModuleV2` / `ImmutableModuleV3` |
| `RedisValueBinder…objectMapper(...)` | `RedisCacheCreator(connectionFactory, JsonCodec.jsonCodec())` + `AbstractKCacheFactory` |
| `binLog.accept(table, JsonNode, JsonNode)` | `accept(table, Node, Node)` with `org.babyfish.jimmer.jackson.codec.Node` |
| `@org.babyfish.jimmer.client.Api` | `@org.babyfish.jimmer.client.meta.Api` (or `@EnableImplicitApi`) |
| `DataFetchingEnvironments.createQuery(Book.class, env)` (GraphQL) | `DataFetchingEnvironments.createFetcher(Book.class, env)` / Kotlin `env.toFetcher<Book>()` |
| `@ThrowsAll` | Kotlin `@Throws(XxxException::class)` |
| `SpringClients` | `org.babyfish.jimmer.spring.SqlClients` |
| kapt / Maven for Kotlin | Gradle + KSP only |

## Coming from JPA/Hibernate/Exposed

- No session, dirty checking, lazy loading or proxies: entities are immutable snapshots; read with fetchers/DTOs,
  write with save commands. `data class`-like `copy { }` replaces setters.
- No cascade or owning-side rules: the shape of the object passed to `save` decides what is written.
- `@OneToMany` always needs `mappedBy`.
- Kotlin nullability *is* the column/association nullability — no extra annotations.
- Optional filters need no `if` chains: `` `eq?` ``, `` `ilike?` ``, `` `between?` ``, `whereIf`, specifications.

## Review checklist

Entities and mapping
- [ ] One Jimmer type per file; Kotlin nullability matches the schema; references to filtered/remote/fake-FK targets are nullable.
- [ ] Entities saved without ids have a `@Key`; `@KeyUniqueConstraint` only with a matching unique constraint.
- [ ] `@OnDissociate` on many-to-one props whose parents get replaced or deleted.

Queries
- [ ] Optional parameters use `?` predicates; empty collections are handled before `` `valueIn?` `` if "no filter" is meant.
- [ ] Column on the left of comparisons; nullable references use `` `store?` `` when rows without them must survive.
- [ ] Result shapes come from fetchers/DTOs; reusable fetchers are top-level/companion `val`s.
- [ ] Paging uses `fetchPage`/`fetchSlice`/`fetchSpringPage` with a deterministic `orderBy`.

Saves and deletes
- [ ] Association mode is explicit for every `save(e, mode)` / `saveCommand(e, mode)` / repository `save(e, mode)` with loaded associations.
- [ ] PATCH endpoints use `dynamic`/`fuzzy` inputs (or entities with only the changed props).
- [ ] Mutations run in `@Transactional`; unique violations are translated with `object : ExceptionTranslator<…>`.
- [ ] `setUpdateWhere`/optimistic-lock lambdas keep the column on the left; callers check `isAccepted`.

Infrastructure
- [ ] `jimmer.language: kotlin`; filters/customizers/initializers are the `K*` variants.
- [ ] DraftInterceptors fill only unloaded props, never touch id/key, set `ignoreIdOnly()` when appropriate.
- [ ] Caches: a trigger path is wired, resolvers implement `getAffectedSourceIds`, ordered association fetchers include the order props, BinLog readers registered for `BigDecimal`.
- [ ] Controllers returning entities use `@FetchBy` with companion-object fetchers matching the ones used; errors declared with `@Throws`.
