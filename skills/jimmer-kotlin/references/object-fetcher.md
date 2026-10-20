# Object fetchers and DTO projections (Kotlin)

## Contents
- [What a fetcher does](#what-a-fetcher-does)
- [Fetcher DSL](#fetcher-dsl)
- [Association field configuration](#association-field-configuration)
- [Recursive associations](#recursive-associations)
- [View, calculated and polymorphic properties](#view-calculated-and-polymorphic-properties)
- [Where fetchers are accepted](#where-fetchers-are-accepted)
- [Output DTOs instead of fetchers](#output-dtos-instead-of-fetchers)
- [Dynamic fetchers from strings](#dynamic-fetchers-from-strings)
- [Performance notes](#performance-notes)

## What a fetcher does

A `Fetcher<E>` describes the shape of the returned object graph (like a GraphQL selection, type-safe). The root
query loads root columns; each association is loaded by **batched** secondary queries (`where FK in (...)`), so
there is no N+1. Unfetched properties are unloaded and absent from JSON.

## Fetcher DSL

```kotlin
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher

val BOOK_DETAIL: Fetcher<Book> = newFetcher(Book::class).by {   // keep reusable fetchers in top-level/companion vals
    allScalarFields()            // scalar columns (not associations, formulas, transients, @ExcludeFromAllScalars, logical-deleted flag)
    tenant(false)                // remove a field added by allScalarFields()
    store {                      // reference with child fetcher
        name()
    }
    authors {                    // collection
        firstName()
        lastName()
    }
}

sqlClient.createQuery(Book::class) {
    select(table.fetchBy {       // inline form (generated extension)
        name()
        store()                  // id-only reference: {"store":{"id":2}}
        authorIds()              // with @IdView
    })
}
```

- The id is always fetched. `allTableFields()` = scalars + FK references as id-only objects (same as `select(table)`).
- `store(IdOnlyFetchType.RAW)` takes the FK as-is without checking the target against filters.
- Extend a fetcher: `newFetcher(Book::class).by(BOOK_DETAIL) { price() }`.

## Association field configuration

```kotlin
newFetcher(Book::class).by {
    store(ReferenceFetchType.JOIN_ALWAYS) {        // load via JOIN in the root SQL
        name()
    }
    authors({                                       // first lambda: field config, second: child shape
        batch(32)                                   // ids per secondary query (defaults: 128 refs, 16 lists)
        limit(10)                                   // per-parent paging (limit, offset = 0): UNION ALL of per-parent sub-selects
        filter {                                    // property-level where/orderBy
            where(table.firstName ilike "a")
            orderBy(table.lastName)
        }
    }) {
        firstName()
        lastName()
    }
}
```

| Config | Applies to | Meaning |
|---|---|---|
| `batch(n)` | all associations | batch size of secondary queries |
| `filter { where(...); orderBy(...) }` | lists, nullable references | filter/sort associated rows (bypasses association caches; rejected on non-null references) |
| `limit(limit, offset)` | lists | per-parent pagination |
| `fetchType(ReferenceFetchType)` | references | `SELECT` (default), `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS`, `AUTO` |
| `depth(n)` / `recursive { ... }` | recursive props | see below |

## Recursive associations

```kotlin
val roots = sqlClient.createQuery(TreeNode::class) {
    where(table.parentId.isNull())
    select(table.fetchBy {
        name()
        `childNodes*`()                              // unlimited depth
    })
}.execute()

newFetcher(TreeNode::class).by { name(); `childNodes*` { depth(2) } }
newFetcher(TreeNode::class).by { name(); `childNodes*` { recursive { entity.name != "Clothing" } } }
newFetcher(TreeNode::class).by { name(); `parent*`() }  // upwards
```

One batched query per level. A node whose recursion stopped has the property unloaded (not empty).

## View, calculated and polymorphic properties

```kotlin
newFetcher(Book::class).by { allScalarFields(); storeId(); authorIds() }       // @IdView
newFetcher(Author::class).by { fullName(); sqlFullName() }                     // @Formula (Kotlin getter / SQL)
newFetcher(BookStore::class).by { name(); avgPrice() }                         // @Transient resolver, batched
newFetcher(Client::class).by {                                                  // inheritance
    name()
    forType(Organization::class) { taxCode() }
    forType(Person::class) { firstName(); lastName() }
}
```

A Kotlin `@Formula` getter loads its dependencies; only the formula property is serialized.

## Where fetchers are accepted

```kotlin
sqlClient.createQuery(Book::class) { select(table.fetch(fetcher)) }
sqlClient.findById(fetcher, 1L)                       // null if absent
sqlClient.findOneById(fetcher, 1L)                    // throws if absent
sqlClient.findByIds(fetcher, listOf(1L, 2L))
sqlClient.findMapByIds(fetcher, listOf(1L, 2L))
sqlClient.findAll(fetcher) { where(table.edition eq 1); orderBy(table.name) }
sqlClient.entities.findAll(fetcher) { asc(Book::name) }
sqlClient.saveCommand(book).execute(fetcher).modifiedEntity   // re-read after save
table.fetch(null)                                     // null fetcher = all table fields
```

With the Spring starter, annotate controller return types with `@FetchBy("FETCHER_NAME")` (`references/spring-boot.md`).

## Output DTOs instead of fetchers

```kotlin
val views: List<BookView> = sqlClient.createQuery(Book::class) {
    where(table.edition eq 1)
    select(table.fetch(BookView::class))
}.execute()
val one: BookView? = sqlClient.findById(BookView::class, 1L)
val all: List<BookView> = sqlClient.findAll(BookView::class) { where(table.edition eq 1) }
val f: Fetcher<Book> = BookView.METADATA.fetcher      // reuse the DTO's shape
val view = BookView(entity)                           // entity → DTO
val back: Book = view.toEntity()                      // DTO → entity
```

DTO association options (`!fetchType`, `!batch`, `!limit`, `!where`, `!orderBy`, `!filter`, `!depth`,
`!recursion`) are declared in the `.dto` file — see `references/dto-language.md`.

## Dynamic fetchers from strings

`FetcherCompiler.compile("{ name store { name } }", Book::class.java)` (`org.babyfish.jimmer.sql.fetcher.compiler`)
needs `org.antlr:antlr4-runtime:4.13.0` at runtime. Only for trusted callers.

## Performance notes

- One extra query per fetched association per batch; use `JOIN_ALWAYS` for small many-to-one lookups.
- Global filters and logical deletion apply to fetched associations.
- `limit(...)` on a list field turns each batch into a `UNION ALL` of per-parent limited sub-selects — heavier SQL.
- With association caches, include the association's `orderedProps` in the child fetcher (see `references/cache.md`).
