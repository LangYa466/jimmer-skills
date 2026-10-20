# Object fetchers and DTO projections (Java)

## Contents
- [What a fetcher does](#what-a-fetcher-does)
- [Fetcher syntax](#fetcher-syntax)
- [Association field configuration](#association-field-configuration)
- [Recursive associations](#recursive-associations)
- [View, calculated and polymorphic properties](#view-calculated-and-polymorphic-properties)
- [Where fetchers are accepted](#where-fetchers-are-accepted)
- [Output DTOs instead of fetchers](#output-dtos-instead-of-fetchers)
- [Dynamic fetchers from strings](#dynamic-fetchers-from-strings)
- [Performance notes](#performance-notes)

## What a fetcher does

A `Fetcher<E>` describes the **shape** of the returned object graph: which scalar columns, which associations,
and the shape of associated objects — like a GraphQL selection, but type-safe and in-process. The root query
loads root columns (plus joined references if requested); every association is then loaded with **batched**
secondary queries (`where FK in (...)`), so there is no N+1 problem. The result is a normal immutable entity whose
unfetched properties are unloaded (and absent from JSON).

## Fetcher syntax

```java
Fetcher<Book> fetcher = BookFetcher.$             // or Fetchers.BOOK_FETCHER
        .allScalarFields()                        // all scalar columns: not associations, @Formula/@Transient,
                                                  //  @ExcludeFromAllScalars props or the logical-deleted flag
        .tenant(false)                            // negative: remove a field added by allScalarFields()
        .store(                                   // reference association with a child fetcher
                BookStoreFetcher.$.name()
        )
        .authors(                                 // collection association
                AuthorFetcher.$.firstName().lastName()
        );
```

- The id is always fetched implicitly.
- `allTableFields()` = scalar fields + FK-based references as id-only objects (the same shape as `select(table)`).
- `store()` with no argument fetches an **id-only** associated object (`{"store":{"id":2}}`); with `@IdView`,
  `storeId()` gives `"storeId":2` instead (same SQL).
- `store(IdOnlyFetchType.RAW)` takes the FK value as-is without checking the target against global filters.
- Fetchers are immutable; every call returns a new fetcher. Keep reusable fetchers in `static final` fields.

## Association field configuration

```java
BookFetcher.$
        .store(
                ReferenceFetchType.JOIN_ALWAYS,           // load the reference with a LEFT/INNER JOIN in the root SQL
                BookStoreFetcher.$.name()
        )
        .authors(
                AuthorFetcher.$.allScalarFields(),
                cfg -> cfg
                        .batch(32)                         // ids per secondary query (default list batch size 16)
                        .limit(10, 0)                      // per-parent paging; forces batch(1) semantics
                        .filter(args -> {                  // property-level filter (where/orderBy on the child table)
                            args.where(args.getTable().firstName().ilike("a"));
                            args.orderBy(args.getTable().lastName());
                        })
        );
```

| Config | Applies to | Meaning |
|---|---|---|
| `batch(n)` | all associations | secondary query batch size (global defaults: `setDefaultBatchSize` 128 for references, `setDefaultListBatchSize` 16 for lists) |
| `filter(args -> ...)` | all associations | extra `where`/`orderBy` for associated rows (associations with filters bypass association caches) |
| `limit(limit, offset)` | collections | per-parent pagination (executes one query per parent) |
| `fetchType(ReferenceFetchType)` | references | `SELECT` (default), `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS`, `AUTO` (use global `setDefaultReferenceFetchType`) |
| `depth(n)` / `recursive(strategy)` | recursive props | see below |

Joined references respect nullability: non-null FK → inner join, nullable → left join. `setMaxJoinFetchDepth` limits nesting.

## Recursive associations

For self-referencing props the fetcher gets `recursiveXxx(...)` methods:

```java
TreeNodeTable node = TreeNodeTable.$;
List<TreeNode> roots = sqlClient.createQuery(node)
        .where(node.parentId().isNull())
        .select(node.fetch(
                TreeNodeFetcher.$
                        .name()
                        .recursiveChildNodes()                         // unlimited depth
        ))
        .execute();

TreeNodeFetcher.$.name().recursiveChildNodes(cfg -> cfg.depth(2));      // limited depth
TreeNodeFetcher.$.name().recursiveChildNodes(cfg -> cfg.recursive(args ->
        !args.getEntity().name().equals("Clothing")));                   // per-node decision
TreeNodeFetcher.$.name().recursiveParent().recursiveChildNodes();       // both directions
```

Each level is one batched query. A node whose recursion stopped has the property unloaded (not empty), so the
client can tell "not fetched" from "no children".

## View, calculated and polymorphic properties

```java
BookFetcher.$.allScalarFields().storeId().authorIds()            // @IdView props
StudentFetcher.$.name().courses(CourseFetcher.$.name())          // @ManyToManyView
AuthorFetcher.$.fullName()                                       // @Formula: loads dependencies, shows only fullName
// note: a Java @Formula reports isLoaded()==true whenever its dependencies are loaded, but it is only
// serialized (visible) when fetched explicitly
BookStoreFetcher.$.name().avgPrice()                             // @Transient resolver (batched)
BookStoreFetcher.$.name().newestBooks(BookFetcher.$.name())      // association-typed transient prop
ClientFetcher.$.name()                                           // inheritance root
        .forType(OrganizationFetcher.$.taxCode())                // subtype-specific fields
        .forType(PersonFetcher.$.firstName().lastName())
```

## Where fetchers are accepted

```java
sqlClient.createQuery(t).select(t.fetch(fetcher)).execute();
sqlClient.findById(fetcher, 1L);                         // null if absent
sqlClient.findOneById(fetcher, 1L);                      // throws if absent
sqlClient.findByIds(fetcher, List.of(1L, 2L));
sqlClient.findMapByIds(fetcher, List.of(1L, 2L));
sqlClient.getEntities().findAll(fetcher, BookProps.NAME, BookProps.EDITION.desc()); // sorted by typed props
sqlClient.saveCommand(book).execute(fetcher).getModifiedEntity();  // re-read the saved object in this shape
t.fetch(null)                                            // a null fetcher means "all table fields" — handy for optional params
```

In Spring MVC, annotate return types with `@FetchBy("FETCHER_CONSTANT")` so generated OpenAPI/TypeScript types
reflect the fetched shape (see spring-boot reference).

## Output DTOs instead of fetchers

A view DTO declared in a `.dto` file carries its own fetcher:

```java
List<BookView> views = sqlClient.createQuery(t).where(...).select(t.fetch(BookView.class)).execute();
BookView one = sqlClient.findById(BookView.class, 1L);
List<BookView> some = sqlClient.findByIds(BookView.class, List.of(1L, 2L));
Fetcher<Book> f = BookView.METADATA.getFetcher();        // reuse the DTO's shape
BookView converted = new BookView(entity);               // entity -> DTO (entity must contain the shape)
Book back = converted.toEntity();                        // DTO -> entity
```

Association options for output DTOs are declared in the `.dto` file (`!fetchType(JOIN_ALWAYS)`, `!batch(n)`,
`!limit(n)`, `!where(...)`, `!orderBy(...)`, `!filter(Class)`, `!depth(n)`, `!recursion(Class)`) — see dto-language.

Prefer returning entities + fetchers internally (no class explosion) and DTOs at API boundaries when you need a
stable, documented type.

## Dynamic fetchers from strings

```java
Fetcher<Book> f = FetcherCompiler.compile("{ name store { name } authors { firstName } }", Book.class);
```
`org.babyfish.jimmer.sql.fetcher.compiler.FetcherCompiler` needs `org.antlr:antlr4-runtime:4.13.0` on the runtime
classpath (`jimmer-sql` declares ANTLR as compile-only). Only expose it to trusted callers (it lets clients choose
arbitrarily deep shapes).

## Performance notes

- Each fetched association = one extra query per batch, regardless of row count. Use `JOIN_ALWAYS` for small
  many-to-one lookups on hot paths.
- Global filters and logical deletion apply to associated objects automatically.
- `limit(...)` on a collection field disables batching (one query per parent) — use sparingly.
- With caches enabled, associations are served from association caches unless a property-level filter is used.
