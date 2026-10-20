# Query DSL (Java)

## Contents
- [Query lifecycle](#query-lifecycle)
- [Execution methods](#execution-methods)
- [Predicates](#predicates)
- [Dynamic queries](#dynamic-queries)
- [Joins](#joins)
- [Collection associations: implicit subqueries and TableEx](#collection-associations-implicit-subqueries-and-tableex)
- [Subqueries](#subqueries)
- [Projections, tuples and typed tuples](#projections-tuples-and-typed-tuples)
- [Aggregation and grouping](#aggregation-and-grouping)
- [Expressions](#expressions)
- [Native SQL fragments](#native-sql-fragments)
- [Ordering](#ordering)
- [Paging](#paging)
- [Locking, hints, set operations](#locking-hints-set-operations)
- [Association (middle) table queries](#association-middle-table-queries)
- [Weak joins](#weak-joins)
- [Base queries: derived tables, CTE, recursive CTE](#base-queries-derived-tables-cte-recursive-cte)
- [Inheritance queries](#inheritance-queries)

## Query lifecycle

```java
BookTable t = BookTable.$;                 // or Tables.BOOK_TABLE
List<Book> books = sqlClient
        .createQuery(t)                   // mutable query: where/orderBy/groupBy/having modify it
        .where(t.name().eq("SQL in Action"))
        .orderBy(t.name().asc(), t.edition().desc())
        .select(t)                        // => immutable typed query; select comes LAST (LINQ style)
        .limit(10, 0)                     // limit/distinct/forUpdate return NEW query objects
        .execute();                       // nothing hits the DB before this
```

- `select(t)` returns entities with all scalar columns + FK ids (equivalent to fetching `allTableFields()`).
- `select(t.fetch(fetcher))` / `select(t.fetch(BookView.class))` return shaped entities / DTOs.
- `select(a, b, c)` returns `Tuple2..Tuple9` (at most 9 selections; use a `@TypedTuple` for more).

## Execution methods

| Method | Notes |
|---|---|
| `execute()` | `List<R>` |
| `fetchOne()` / `fetchOneOrNull()` / `fetchOptional()` | exactly one / at most one row (throws otherwise) |
| `fetchFirst()` / `fetchFirstOrNull()` | first row |
| `exists()` | any row? |
| `map(row -> ...)` | execute and map |
| `forEach(row -> ...)`, `forEach(batchSize, row -> ...)` | cursor-like iteration |
| `stream()` | JDBC-cursor `Stream<R>` — **close it** (try-with-resources); fetchers needing secondary selects are rejected |
| `fetchPage(pageIndex, pageSize)` | `org.babyfish.jimmer.Page<R>` (`getRows`, `getTotalRowCount`, `getTotalPageCount`); index starts at 0 |
| `fetchPage(i, size, SpringPageFactory.getInstance())` | Spring Data `Page` |
| `fetchSlice(limit, offset)` | `Slice<R>` (`getRows`, `isHead`, `isTail`) without count query |
| `fetchUnlimitedCount()` | count ignoring order/limit |

Every method has an overload with an explicit `java.sql.Connection`. Per-query JDBC options:
`createQuery(t).jdbcFetchSize(500).jdbcQueryTimeout(30)...`.

## Predicates

```java
t.name().eq("A")                 t.name().ne("A")
t.price().gt(x)  .ge(x)  .lt(x)  .le(x)
t.price().between(lo, hi)        t.price().notBetween(lo, hi)
t.name().like("Ab")              // %Ab%  (LikeMode.ANYWHERE default)
t.name().like("Ab", LikeMode.START)  // Ab%   (also END, EXACT)
t.name().ilike("ab")             // case-insensitive (ilike or lower(...) like)
t.name().notLike("x")
t.store().isNull()               t.storeId().isNotNull()
t.name().in(List.of("A", "B"))   t.name().notIn(list)
Expression.tuple(t.name(), t.edition()).in(List.of(new Tuple2<>("A", 1), new Tuple2<>("B", 2)))
Predicate.and(p1, p2)  Predicate.or(p1, p2)  Predicate.not(p)   // not() is static; there is no p.not()
```

- `where(p1, p2, ...)` and repeated `where(...)` calls are AND-ed.
- `eq(null)` becomes `is null`, `ne(null)` becomes `is not null`; other operators reject `null` (use the `*If` variants).
- `IN` with one element becomes `=`; on dialects with arrays (H2/Postgres) single-column `IN` becomes `= any(?)`.
  `setInListPaddingEnabled(true)` pads lists to powers of two.
- `table.storeId()` (FK id of a reference association) never joins, with or without an `@IdView`.

## Dynamic queries

Dynamic predicates return `null` when the argument is `null` or `""`, and `where`/`and`/`or` ignore `null`:
`eqIf`, `neIf`, `gtIf`, `geIf`, `ltIf`, `leIf`, `likeIf`, `ilikeIf`, `betweenIf` (either bound may be null), `inIf`.
`inIf(null)` is ignored, but **`inIf(emptyList)` is not** — it matches no rows; check `isEmpty()` yourself if an
empty selection means "no filter".

```java
sqlClient.createQuery(t)
        .where(t.name().ilikeIf(name))
        .where(t.price().betweenIf(minPrice, maxPrice))
        .where(Predicate.or(t.name().eqIf(keyword), t.store().name().eqIf(keyword))) // or() of nulls → null
        .whereIf(edition != null, () -> t.edition().eq(edition))                     // classic style
        .orderByIf(sortByPrice, t.price().desc())
        .select(t)
        .execute();
```

Join paths used only by skipped predicates generate no SQL joins (dynamic join optimization); identical joins are merged.

## Joins

Joins are implicit property navigation; there is no `join()` call for reference associations.

```java
t.store().name().eq("MANNING")              // inner join BOOK_STORE (non-null semantics)
t.store(JoinType.LEFT).website().isNull()   // left outer join
t.store().city().name()                     // chained joins
```

Inner vs left matters: an inner join through a nullable FK drops rows. Use `JoinType.LEFT` when selecting optional columns.

## Collection associations: implicit subqueries and TableEx

Top-level `BookTable` has **no** `authors()` join (to avoid duplicated rows breaking paging). Options:

```java
// 1. implicit subquery (recommended for filters): generates EXISTS(...)
.where(t.authors(a -> a.firstName().ilikeIf(name)))
.where(t.authors(a -> Predicate.or(a.firstName().ilikeIf(n), a.lastName().ilikeIf(n))))

// 2. explicit join through TableEx + distinct
sqlClient.createQuery(t)
        .where(t.asTableEx().authors().firstName().ilike("a"))
        .select(t.id())
        .distinct()
        .execute();

// 3. a real subquery (below)
```
`AuthorTableEx.$` / `TableExes.AUTHOR_TABLE_EX` are used in subqueries, update/delete statements and filters.

## Subqueries

```java
AuthorTableEx a = AuthorTableEx.$;

// IN (subquery)
.where(t.id().in(sqlClient.createSubQuery(a).where(a.firstName().eq("Alex")).select(a.books().id())))

// multi-column IN: newest edition per name
.where(Expression.tuple(t.name(), t.edition()).in(
        sqlClient.createSubQuery(t).groupBy(t.name()).select(t.name(), t.edition().max())))

// correlated EXISTS (no select needed)
.where(sqlClient.createSubQuery(a).where(a.books().eq(t), a.firstName().eq("Alex")).exists())

// scalar subquery in select/orderBy
BookStoreTable s = BookStoreTable.$;
TypedSubQuery<BigDecimal> avg = sqlClient.createSubQuery(t)
        .where(t.store().eq(s))
        .select(t.price().avgAsDecimal().coalesce(BigDecimal.ZERO));
List<Tuple2<BookStore, BigDecimal>> rows = sqlClient.createQuery(s).orderBy(avg.desc()).select(s, avg).execute();

// ANY / ALL
.where(t.id().eq(sqlClient.createSubQuery(a).where(...).select(a.books().id()).any()))
```

Inside a subquery the outer table is referenced directly (`t`). `selectCount()` is a shortcut for `select(count)`.

## Projections, tuples and typed tuples

```java
List<Tuple2<String, Integer>> rows = sqlClient.createQuery(t).select(t.name(), t.edition()).execute();
Map<Long, BigDecimal> map = Tuple2.toMap(rows2);                 // Tuple2.toMultiMap for List values
```

`@TypedTuple` gives named projections (and named derived tables):
```java
@TypedTuple                                   // org.babyfish.jimmer.sql.TypedTuple; must be a class, not a record
public class StoreStatistics {
    private final long storeId;
    private final long bookCount;
    private final BigDecimal avgPrice;
    public StoreStatistics(long storeId, long bookCount, BigDecimal avgPrice) { ... }  // names = field names
    // getters ...
}

List<StoreStatistics> stats = sqlClient.createQuery(t)
        .where(t.storeId().isNotNull())
        .groupBy(t.storeId())
        .select(StoreStatisticsMapper                // generated
                .storeId(t.storeId())
                .bookCount(Expression.rowCount())
                .avgPrice(t.price().avgAsDecimal()))
        .execute();
```
Lombok `@Data` with final fields (`@AllArgsConstructor`) or a no-arg constructor + setters also work.

## Aggregation and grouping

```java
t.count()                       // count(id)
t.id().count(true)              // count(distinct id)
Expression.rowCount()           // count(1)
t.price().sum()  .min()  .max()
t.price().avg()                 // NumericExpression<Double>
t.price().avgAsDecimal()        // NumericExpression<BigDecimal> — use this for money

List<Tuple2<Long, BigDecimal>> rows = sqlClient.createQuery(t)
        .groupBy(t.storeId())
        .having(t.price().avgAsDecimal().gt(new BigDecimal(50)))
        .select(t.storeId(), t.price().avgAsDecimal())
        .execute();
```

## Expressions

```java
t.price().plus(BigDecimal.ONE) .minus(x) .times(x) .div(x) .rem(x)
t.firstName().concat(Expression.string().value(" "), t.lastName())
t.name().length()  .lower()  .upper()  .trim()
t.website().coalesce("N/A")                            // two-arg coalesce
t.website().coalesceBuilder().or(t.name()).or("N/A").build()

Expression.string().caseBuilder()                      // searched case
        .when(t.price().lt(new BigDecimal(30)), "Cheap")
        .when(t.price().gt(new BigDecimal(70)), "Expensive")
        .otherwise("Normal")
Expression.string().caseBuilder(t.name())              // simple case
        .when("SQL in Action", "Matched").otherwise("Other")

Expression.string().value("x")  Expression.numeric().value(3L)  Expression.comparable().value(now)
Expression.nullValue(Boolean.class)                    // null literal must be typed
Expression.constant(1)                                 // inlined numeric constant (only numbers; avoid in general)
```
Java uses type-guided factories: `Expression.string()`, `numeric()`, `comparable()`, `any()`.

## Native SQL fragments

```java
Predicate.sql("regexp_like(%e, %v)", it -> it.expression(t.name()).value("^SQL"))   // %e expression, %v value
Expression.numeric().sql(Integer.class, "rank() over(order by %e desc)", t.price())
Expression.numeric().sql(Integer.class, "rank() over(partition by %e order by %e desc)",
        new Expression[] { t.storeId(), t.price() })
Expression.string().sql("upper(%e)", t.name())
```
On Postgres JSON operators, write `??` for a literal `?` (JDBC placeholder).

## Ordering

```java
.orderBy(t.name())                          // asc by default; don't mix: orderBy(t.name(), t.edition().desc()) won't compile
.orderBy(t.edition().desc(), t.score().desc().nullsLast())
.orderBy(Order.makeOrders(t, "store.name asc, name, edition desc")) // client-specified, supports reference paths
.orderBy(SpringOrders.toOrders(t, pageable.getSort()))               // Spring Data Sort (starter)
```

## Paging

```java
Page<Book> page = sqlClient.createQuery(t)
        .where(...)
        .orderBy(t.name().asc(), t.edition().desc())
        .select(t.fetch(fetcher))
        .fetchPage(0, 10);
```

- The count query is generated automatically: order-by and unnecessary left joins / fetchers are removed.
- `setOffsetOptimizingThreshold(n)` (`jimmer.offset-optimizing-threshold`) rewrites deep offsets as an id subquery;
  reverse-sorting optimization helps the last pages.
- Manual control: `query.fetchUnlimitedCount()` + `query.limit(size, offset).execute()`.

## Locking, hints, set operations

```java
.select(t).forUpdate()                                    // FOR UPDATE
.select(t).forUpdate(LockMode.SHARE, LockWait.NO_WAIT)    // dialect-dependent variants
.select(t).hint("/*+ INDEX(...) */")
TypedRootQuery<Long> q = q1.union(q2);   // union, unionAll, minus, intersect (also static TypedRootQuery.union(...))
```

## Association (middle) table queries

```java
AssociationTable<Book, BookTableEx, Author, AuthorTableEx> association =
        AssociationTable.of(BookTableEx.class, BookTableEx::authors);
List<Association<Book, Author>> rows = sqlClient
        .createQuery(association)                         // createAssociationQuery(...) is deprecated
        .where(association.source().id().eq(3L))
        .select(association)                              // or association.source(), association.target()
        .execute();
```
`Association<S, T>` exposes `source()`/`target()` (id-only unless you select/fetch more).

## Weak joins

Join two tables without a mapped association:
```java
public class BookAuthorJoin implements WeakJoin<BookTable, AuthorTable> {   // top-level (or static nested) class, no-arg ctor
    @Override
    public Predicate on(BookTable source, AuthorTable target) {
        return source.name().eq(target.lastName());
    }
}
sqlClient.createQuery(t)
        .where(t.asTableEx().weakJoin(BookAuthorJoin.class).firstName().eq("Alex"))
        .select(t.id()).distinct().execute();
```

## Base queries: derived tables, CTE, recursive CTE

`createBaseQuery(...)` builds a reusable source (derived table / CTE) consumed by `createQuery(baseTable)`,
`createBaseQuery(baseTable)`, `createInsert`/`createUpsert`. Inner selections of table type are shaped from the
outside ("reverse projection propagation"): the outer fetcher decides which columns the inner query exports.

```java
BookTable book = BookTable.$;
AuthorTableEx author = AuthorTableEx.$;
BaseTable2<BookTable, NumericExpression<Integer>> ranked = sqlClient
        .createBaseQuery(book)
        .addSelect(book)                                   // chained addSelect = positional columns _1.._9
        .addSelect(Expression.numeric().sql(Integer.class, "dense_rank() over(order by %e desc)",
                sqlClient.createSubQuery(author).where(author.books().id().eq(book.id())).selectCount()))
        .asBaseTable();                                    // asCteBaseTable() renders WITH ... AS (...)
List<Book> top = sqlClient.createQuery(ranked)
        .where(ranked.get_2().eq(1))
        .select(ranked.get_1().fetch(BookFetcher.$.name()))
        .execute();

// select(table) keeps the table type; select(TypedTupleMapper) gives a named table (e.g. StoreStatisticsTable)
BookStoreTable manning = sqlClient.createBaseQuery(BookStoreTable.$)
        .where(BookStoreTable.$.name().eq("MANNING"))
        .select(BookStoreTable.$)
        .asBaseTable();
```

Recursive CTE (only `union all`):
```java
TreeNodeTable node = TreeNodeTable.$;
BaseTable2<TreeNodeTable, NumericExpression<Integer>> tree = TypedBaseQuery.unionAllRecursively(
        sqlClient.createBaseQuery(node)
                .where(node.parentId().isNull())
                .addSelect(node)
                .addSelect(Expression.constant(1)),
        recursiveRef -> {
            MutableRecursiveBaseQuery<BaseTable2<TreeNodeTable, NumericExpression<Integer>>> q =
                    sqlClient.createBaseQuery(node, recursiveRef, (t2, r) -> t2.parentId().eq(r.get_1().id()));
            return q.addSelect(node).addSelect(q.recursive().get_2().plus(Expression.constant(1)));
        }
).asCteBaseTable();
```
Do not apply fetchers/DTOs to selections **inside** a base query; apply them in the outer query.
Imports: `BaseTable2..` live in `org.babyfish.jimmer.sql.ast.table.base`; `TypedBaseQuery`, `MutableRecursiveBaseQuery`
in `org.babyfish.jimmer.sql.ast.query`.

> **Pitfall (verified on 0.12.4):** Jimmer analyzes weak-join *lambdas* (such as the recursive join lambda above) with
> a bundled ASM that does not understand Java 11+ `NestMembers`. If the class that declares the lambda also declares
> nested/inner/anonymous classes, execution fails with `UnsupportedOperationException: NestMember requires ASM7`.
> Keep such lambdas in classes without nested classes, or pass a `WeakJoin` implementation class instead of a lambda.

## Inheritance queries

```java
ClientTable c = ClientTable.$;
sqlClient.createQuery(c)                                   // returns Organization/Person instances
        .where(c.instanceOf(Organization.class))           // also exactType(...)
        .select(c.id(), c.treatAs(OrganizationTable.class).taxCode())  // tryTreatAs = left/nullable semantics
        .execute();
OrganizationTable orgTable = OrganizationTable.$;
sqlClient.createQuery(orgTable).typeMatchMode(TypeMatchMode.EXACT).select(orgTable).execute();
// root queries default to POLYMORPHIC; EXACT on an abstract type (no @DiscriminatorValue) throws.
// TypeMatchMode is org.babyfish.jimmer.sql.ast.TypeMatchMode (AUTO / EXACT / POLYMORPHIC)
```
Fetch subtype props with `ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())`.
