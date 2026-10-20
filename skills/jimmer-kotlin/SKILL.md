---
name: jimmer-kotlin
description: Expert guide for the Jimmer ORM (babyfish-ct/jimmer, org.babyfish.jimmer) with the Kotlin API — KSqlClient, newKSqlClient, @Entity interfaces with val properties, Book { } / copy { } drafts, generated table extension properties and fetchBy/newFetcher DSL from jimmer-ksp, typed SQL DSL (eq, `eq?`, valueIn, subQuery), object fetchers, the .dto DTO language (view/input/specification), save commands (SaveMode/AssociatedSaveMode), delete/update statements, KFilter global filters, logical deletion, DraftInterceptor, cache, jimmer-spring-boot-starter (jimmer.language=kotlin, KRepository, AbstractKotlinRepository), OpenAPI/TypeScript client generation. Use whenever writing, reviewing, debugging or migrating Kotlin code that uses Jimmer, or when the user mentions Jimmer, babyfish, KSqlClient, KSP, jimmer-ksp or src/main/dto in a Kotlin project. 适用于 Jimmer ORM Kotlin 开发、实体映射、DTO 语言、保存指令、查询 DSL。
license: MIT
metadata:
  author: LangYa466
  version: "1.0.0"
  jimmer-version: "0.12.4"
  repository: https://github.com/LangYa466/jimmer-skills
---

# Jimmer ORM — Kotlin

Jimmer is a compile-time ORM for the JVM. Entities are **interfaces with `val` properties**; the KSP processor
(`jimmer-ksp`) generates immutable implementations, drafts, a typed SQL DSL (extension properties) and the fetcher
DSL. Its core idea: read and write **object graphs of arbitrary shape** as a whole.

> Baseline: Jimmer **0.12.4**, Kotlin **2.1.20**, KSP **2.1.20-2.0.1**. Every API shown in this skill was compiled
> and executed against that baseline (+ H2, + Spring Boot 3.5.6 for the starter). Several APIs in older blog posts
> and parts of the official docs no longer exist or behave differently — see [Critical rules](#critical-rules-read-first).

## Mental model

1. **Entities are immutable, dynamic interfaces.** A property can be *unloaded* (absent) — different from `null`.
   Reading an unloaded property throws `UnloadedException`; Jackson omits it.
2. **Create/"modify" objects with drafts**: `Book { name = "X" }`, `book.copy { price = ... }`, `Book(base) { }`.
3. **Read graphs with fetchers** (`table.fetchBy { allScalarFields(); store { name() } }`) or generated DTO classes.
   No N+1: associations are batch-loaded.
4. **Write graphs with save commands** (`sqlClient.save(entity)`): unloaded props are untouched, loaded props are
   written, associations are diffed according to `SaveMode` / `AssociatedSaveMode`.
5. **Everything goes through `KSqlClient`** — stateless and thread-safe (no session, dirty checking or lazy proxies).

## Generated code (per entity `Book`, package of the entity)

| Generated (`build/generated/ksp/main/kotlin`) | Use it for |
|---|---|
| `BookDraft.kt`: `Book { }`, `Book(base) { }`, `Book.copy { }`, `BookDraft` | create / copy-modify objects |
| `BookProps.kt`: `table.name`, `table.store`, `` table.`store?` ``, `table.storeId`, `table.authors { }`, `table.fetchBy { }`, `BookProps.NAME` | SQL DSL extension properties, `TypedProp` constants |
| `BookFetcher.kt`: `newFetcher(Book::class).by { }` (`BookFetcherDsl`) | object fetcher DSL |
| from `src/main/dto/**.dto` | `View<Book>` / `Input<Book>` / `KSpecification<Book>` classes |

There are no `Immutables`/`Tables`/`Fetchers` summary classes in Kotlin. Most DSL operators are extension functions:
`import org.babyfish.jimmer.sql.kt.ast.expression.*` plus your model package.

## Quick start

```kotlin
// build.gradle.kts — Spring Boot users: jimmer-spring-boot-starter instead of jimmer-sql-kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
}
dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:0.12.4")
    ksp("org.babyfish.jimmer:jimmer-ksp:0.12.4")
}
```

```kotlin
// Book.kt — ONE Jimmer type per file
@Entity
interface Book {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    @Key val name: String                     // business key used by save commands
    @Key val edition: Int
    val price: BigDecimal
    @ManyToOne val store: BookStore?          // Kotlin nullability = column/association nullability
    @ManyToMany val authors: List<Author>
    @IdView val storeId: Long?
}
```

```kotlin
val sqlClient = newKSqlClient {
    setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
    setDialect(PostgresDialect())
}                                              // the Spring Boot starter creates it (jimmer.language: kotlin)

val books: List<Book> = sqlClient.createQuery(Book::class) {
    where(table.name `ilike?` keyword)             // `?` predicates: skipped when the argument is null
    where(table.store.name `eq?` storeName)        // implicit (inner) join
    orderBy(table.name.asc(), table.edition.desc())
    select(table.fetchBy {
        allScalarFields()
        store { name() }
    })
}.execute()

val id = sqlClient.save(Book {
    name = "SQL in Action"
    edition = 1
    price = BigDecimal("49.9")
    storeId = 2L
}).modifiedEntity.id
```

## Critical rules (read first)

1. **One Jimmer type per `.kt` file** (`@Entity`/`@MappedSuperclass`/`@Embeddable`/`@Immutable`); otherwise KSP
   fails with "declares several types".
2. **Association-mode defaults differ by overload:** `save(e)` = `UPSERT` + `REPLACE`; `save(e, SaveMode.X)` →
   **`MERGE`**; `saveCommand(e, SaveMode.X)` → **`REPLACE`**; `KotlinRepository.save(e, mode)` → `REPLACE`. `REPLACE`
   dissociates associated rows missing from the collection you pass. Pass `AssociatedSaveMode` explicitly.
3. **No `insert()/update()/merge()` on `KSqlClient`.** Use `save(e, SaveMode.INSERT_ONLY, AssociatedSaveMode.APPEND)`,
   `save(e, SaveMode.UPDATE_ONLY, ...)` or `save(e) { setMode(...) }` (repository `insert`/`update` shortcuts are `@Deprecated`).
4. **Unloaded ≠ null.** Props not assigned in `Book { }` are unloaded and untouched by saves; `null` writes NULL.
   Check with `isLoaded(obj, Book::store)` (`org.babyfish.jimmer.kt`).
5. **Objects without id need complete `@Key` values** (or `setKeyProps`, `INSERT_ONLY`), else
   `SaveException.NeitherIdNorKey`; a root keyed by `(name, parent)` must assign `parent = null` explicitly.
6. **Keep the column on the left** of comparisons (`table.price lt x`). 0.12.4 renders `value(x) gt table.price` with
   the *negated* operator — silently wrong results.
7. **`` `valueIn?` `` ignores `null` but not an empty collection** (renders `1 = 0`). Reference paths
   (`table.store.name`) are inner joins even inside `or(...)` — use `` table.`store?`.name `` to keep rows without one.
8. **Top-level queries cannot join collection associations** — use implicit subqueries `table.authors { ... }`,
   `exists(...)`, `subQuery(...)`, or `table.asTableEx().authors` + `.distinct()`.
9. **Kotlin `@Formula` getters are fetcher-only**; SQL formulas (`@Formula(sql = "...%alias...")`) work in the DSL.
10. **`findById` returns `null` if absent; `findOneById` throws.** With an object cache, a cache hit ignores global
    filters/logical deletion.
11. **`ExceptionTranslator` must be an `object :` expression or class**, not a SAM lambda ("Illegal exception
    translator type").
12. **`sqlClient.filters { }` (`KFilterDsl`) has no `disableAll()`**, and disabling user filters never lifts
    logical deletion — use `setBehavior(LogicalDeletedBehavior.IGNORED)`.
13. **Spring:** set `jimmer.language: kotlin` (else a `JSqlClient` is created); register `KFilter`/`KCustomizer`/
    `KInitializer` beans — Java `Filter`/`Customizer`/`Initializer` beans are ignored in Kotlin mode.
14. **`KRepository` derived-query params are dynamic only with `@DynamicParam`**; a nullable Kotlin type alone throws
    `NullPointerException` for `null`.
15. **Jackson 0.12:** `ImmutableModuleV2` (Jackson 2) / `ImmutableModuleV3`; the starter registers it. Use
    `jackson-module-kotlin` for DTOs.

## Task router — read the reference you need

| Task | Reference |
|---|---|
| Gradle + KSP setup, KSP options, multi-module, `newKSqlClient`, dialects, transactions, Jackson | [references/setup-and-config.md](references/setup-and-config.md) |
| Any annotation: ids, keys, associations, nullity, embedded, JSON, enums, formulas, transient resolvers, `@IdView`, `@ManyToManyView`, logical delete, inheritance, naming, `ScalarProvider` | [references/entity-mapping.md](references/entity-mapping.md) |
| Creating/copying objects, drafts, `isLoaded`/`unload`/`makeIdOnly`, JSON, equality | [references/immutable-objects.md](references/immutable-objects.md) |
| Queries: imports, predicates, dynamic `?` predicates, joins, subqueries, tuples, grouping, expressions, native SQL, paging, association tables, weak joins, CTE/base queries, inheritance | [references/query-dsl.md](references/query-dsl.md) |
| Shaping results: `fetchBy`/`newFetcher`, field config, recursion, id views, DTO projections, `find*` APIs | [references/object-fetcher.md](references/object-fetcher.md) |
| `.dto` files: view / input / specification, macros, `id()`, `flat`, `fold`, null modes and `isXxxLoaded`, QBE, fragments, `#types` | [references/dto-language.md](references/dto-language.md) |
| Saving graphs: entry-point defaults, modes, keys, dissociation, results, upsert mask, conditional update, locking, exception translation | [references/save-command.md](references/save-command.md) |
| Deleting, `createUpdate`/`createDelete`, insert/upsert from a query, association (middle table) API | [references/delete-and-dml.md](references/delete-and-dml.md) |
| `KFilter`, logical deletion behavior, `DraftInterceptor`/`DraftPreProcessor`, triggers, binlog | [references/filters-interceptors-events.md](references/filters-interceptors-events.md) |
| Caching (object/association/calculated/multi-view), Redis/Caffeine, `AbstractKCacheFactory` | [references/cache.md](references/cache.md) |
| Spring Boot starter, `AbstractKotlinRepository`, `KRepository`, `@FetchBy`, error families, OpenAPI/TypeScript, Spring GraphQL, multiple data sources | [references/spring-boot.md](references/spring-boot.md) |
| Compile errors, runtime exceptions, migration from older versions, review checklist | [references/pitfalls.md](references/pitfalls.md) |

## Everyday templates

**Reusable fetchers** (top-level or companion-object `val`s):
```kotlin
val BOOK_DETAIL = newFetcher(Book::class).by {
    allScalarFields()
    store { name() }
    authors({ batch(32) }) { firstName(); lastName() }
}
```

**Paging with dynamic filters and client-provided sort:**
```kotlin
val page = sqlClient.createQuery(Book::class) {
    where(table.price.`between?`(minPrice, maxPrice))
    where += table.authors {                          // implicit subquery on a collection
        or(firstName `ilike?` author, lastName `ilike?` author)
    }
    orderBy(table.makeOrders(sortCode))               // e.g. "store.name asc, edition desc"
    select(table.fetch(BOOK_DETAIL))
}.fetchPage(pageIndex, pageSize)                      // pageIndex starts at 0
```

**DTO in, DTO out** (`src/main/dto/Book.dto`):
```
export com.example.model.Book -> package com.example.dto

BookView { #allScalars  store { id name }  authors { id firstName lastName } }
input BookInput { #allScalars  id(store)  id(authors) as authorIds }
specification BookSpecification { like/i(name)  ge(price)  le(price)  associatedIdIn(store) as storeIds }
```
```kotlin
val views: List<BookView> = sqlClient.createQuery(Book::class) {
    where(spec)
    select(table.fetch(BookView::class))
}.execute()
val id = sqlClient.save(input).modifiedEntity.id
```

**Partial update without loading:**
```kotlin
sqlClient.save(Book { id = bookId; price = newPrice }, SaveMode.UPDATE_ONLY)
```

**Delete** (logical if the entity has `@LogicalDeleted`, physical otherwise):
```kotlin
sqlClient.deleteById(Book::class, id)
sqlClient.deleteById(Book::class, id, DeleteMode.PHYSICAL)
```

## Working style for agents

- Before writing DSL code, open the entity and confirm property names, nullability, `@Key`s and `@IdView`s.
  `table.storeId` exists for any `@ManyToOne store` even without an `@IdView`.
- After changing entities or `.dto` files, **rebuild** so KSP regenerates code (and make sure `.dto` files are KSP
  inputs); stale generated code is the #1 cause of "Unresolved reference".
- If an operator doesn't resolve, check imports (`org.babyfish.jimmer.sql.kt.ast.expression.*`) before anything else.
- Prefer fetchers/DTOs over manual tuples; prefer `save()` with explicit modes over hand-written updates.
- Jimmer semantics differ from JPA/Hibernate/Exposed (no cascade config, no lazy loading, no session) — consult the
  reference instead of guessing.
