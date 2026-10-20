# Query DSL (Kotlin)

## Contents
- [Imports](#imports)
- [Query lifecycle](#query-lifecycle)
- [Execution methods](#execution-methods)
- [Predicates](#predicates)
- [Dynamic queries](#dynamic-queries)
- [Joins](#joins)
- [Collection associations](#collection-associations)
- [Subqueries](#subqueries)
- [Projections and typed tuples](#projections-and-typed-tuples)
- [Aggregation and grouping](#aggregation-and-grouping)
- [Expressions](#expressions)
- [Native SQL fragments](#native-sql-fragments)
- [Ordering and paging](#ordering-and-paging)
- [Locking, hints, set operations](#locking-hints-set-operations)
- [Association (middle) table queries](#association-middle-table-queries)
- [Weak joins](#weak-joins)
- [Base queries: derived tables, CTE, recursive CTE](#base-queries-derived-tables-cte-recursive-cte)
- [Inheritance queries](#inheritance-queries)

## Imports

Most operators are extension functions — import them or nothing resolves:

```kotlin
import org.babyfish.jimmer.sql.kt.ast.expression.*        // eq, `eq?`, valueIn, between, like, ilike, and, or, not,
                                                          // count, sum, avgAsDecimal, max, case, coalesce, sql, value, …
import org.babyfish.jimmer.sql.kt.ast.table.makeOrders    // table.makeOrders("name asc, edition desc")
import com.example.model.*                                 // generated XxxProps.kt extension properties (table.name …)
import org.babyfish.jimmer.sql.kt.exists                   // sqlClient.exists(Book::class) { ... }
import org.babyfish.jimmer.sql.kt.createQuery              // reified sqlClient.createQuery<Book, _> { ... }
import org.babyfish.jimmer.sql.kt.ast.query.whereIfNotNull // also whereIfNotEmpty, whereIfNotBlank
```

## Query lifecycle

```kotlin
val books: List<Book> = sqlClient
    .createQuery(Book::class) {               // the lambda configures the query; `table` is KNonNullTable<Book>
        where(table.name eq "SQL in Action")
        orderBy(table.name.asc(), table.edition.desc())
        select(table)                          // the lambda must END with select(...)
    }
    .limit(10)                                 // limit/offset/distinct/forUpdate return new query objects
    .execute()                                 // nothing hits the DB before this

val names: List<String> = sqlClient.executeQuery(Book::class) {   // createQuery + execute
    select(table.name)
}
```

- `select(table)` returns entities with scalar columns + FK ids; `select(table.fetch(fetcher))`,
  `select(table.fetchBy { ... })` or `select(table.fetch(BookView::class))` return shaped entities / DTOs.
- `select(a, b, ...)` returns `Tuple2..Tuple9` (destructurable: `val (name, edition) = row`).
- Reified shortcut: `sqlClient.createQuery<Book, _> { select(table.id) }` (the `_` lets Kotlin infer the result type).

## Execution methods

| Method | Notes |
|---|---|
| `execute()` | `List<R>` |
| `fetchOne()` / `fetchOneOrNull()` | exactly one / at most one |
| `fetchFirst()` / `fetchFirstOrNull()` | first row |
| `exists()` | any row |
| `map { }`, `forEach { }` | iterate |
| `stream()` | JDBC cursor stream — `use { }` it |
| `fetchPage(pageIndex, pageSize)` | `org.babyfish.jimmer.Page<R>` (`rows`, `totalRowCount`, `totalPageCount`), index from 0 |
| `fetchSlice(limit, offset)` | `Slice<R>` (`rows`, `isHead`, `isTail`), no count query |
| `fetchUnlimitedCount()` | count ignoring paging |

All accept an optional `con: Connection?`. JDBC options inside the lambda: `jdbc { fetchSize = 500; queryTimeout = 30 }`.
Other entry points: `sqlClient.exists(Book::class) { where(...) }`, `findAll(fetcher) { where(...) }`,
`findOne(fetcher) { ... }`, `findOneOrNull(...)`.

## Predicates

```kotlin
table.name eq "A"                 table.name ne "A"
table.price gt x                  table.price ge x      table.price lt x      table.price le x
table.price.between(lo, hi)       table.price.notBetween(lo, hi)
table.name like "Ab"              // %Ab%;  table.name.like("Ab", LikeMode.START) → Ab%
table.name ilike "ab"             // case-insensitive
table.storeId.isNull()            table.storeId.isNotNull()
table.name valueIn listOf("A", "B")          table.name valueNotIn list
tuple(table.name, table.edition) valueIn listOf(Tuple2("A", 1), Tuple2("B", 2))
and(p1, p2)   or(p1, p2)   p.not()            // not() is an extension in Kotlin (Java: static Predicate.not)
```

- `where(p1, p2)` and repeated `where(...)` are AND-ed; `where += p` also works.
- `eq`/`ne` accept `null` and render `IS NULL`/`IS NOT NULL` — use `` `eq?` ``/`` `ne?` `` to *skip* the predicate
  instead. The other comparison operators need a non-null value (or their `?` variants).
- `table.storeId` (FK of a reference) never joins.
- **Keep the column on the left.** 0.12.4 renders `value(x) gt table.price` with the negated operator
  (`PRICE <= x`) — silently wrong. Write `table.price lt x`.

## Dynamic queries

Back-quoted `?` operators return `null` when the argument is `null` (or `""` for strings); `where`, `and`, `or`
ignore `null` predicates:

```kotlin
sqlClient.createQuery(Book::class) {
    where(table.name `ilike?` name)                         // `eq?`, `ne?`, `gt?`, `ge?`, `lt?`, `le?`, `like?`, `ilike?`
    where(table.price.`between?`(minPrice, maxPrice))       // either bound may be null
    where(table.storeId `valueIn?` storeIds)                // null → ignored; EMPTY list → `1 = 0` (no rows)
    where(or(table.name `eq?` keyword, table.store.name `eq?` keyword))
    whereIfNotNull(edition) { table.edition eq it }         // also whereIfNotEmpty / whereIfNotBlank
    select(table)
}
```

Joins used only by ignored predicates are not generated. A reference path such as `table.store.name` is an
**inner** join even inside `or(...)` — use `` table.`store?`.name `` when books without a store must survive.

## Joins

Implicit, via generated properties:

```kotlin
table.store.name eq "MANNING"            // inner join (KNonNullTable)
table.`store?`.website.isNull()          // left join (KNullableTable)
table.store.name                         // chain further
```

An inner join through a nullable FK drops rows — use the `?` property for optional data.

## Collection associations

Top-level tables have no `authors` join property (to keep paging correct). Options:

```kotlin
// 1. implicit subquery → EXISTS(...), lambda receiver is the author table
where(table.authors { firstName `ilike?` name })
where += table.authors { or(firstName `ilike?` n, lastName `ilike?` n) }

// 2. TableEx join + distinct
sqlClient.createQuery(Book::class) {
    where(table.asTableEx().authors.firstName ilike "a")
    select(table.id)
}.distinct().execute()
```

## Subqueries

```kotlin
// IN (subquery); inside subQuery `table` is the sub table and `parentTable` the outer one
where(table.id valueIn subQuery(Author::class) {
    where(table.firstName eq "Alex")
    select(table.asTableEx().books.id)
})

// multi-column IN
where(tuple(table.name, table.edition) valueIn subQuery(Book::class) {
    groupBy(table.name)
    select(table.name, max(table.edition).asNonNull())
})

// correlated EXISTS without select
where(exists(wildSubQuery(Author::class) {
    where(table.asTableEx().books eq parentTable, table.firstName eq "Alex")
}))

// scalar subquery in select
sqlClient.createQuery(BookStore::class) {
    val avgPrice = subQuery(Book::class) {
        where(table.store eq parentTable)
        select(avgAsDecimal(table.price))
    }
    select(table, avgPrice)
}
```

`any(subQuery)` / `all(subQuery)` are available for `eq any(...)`-style comparisons.

## Projections and typed tuples

```kotlin
val rows: List<Tuple2<String, Int>> = sqlClient.createQuery(Book::class) {
    select(table.name, table.edition)
}.execute()
val map = rows.associate { it._1 to it._2 }
```

```kotlin
@TypedTuple                                   // org.babyfish.jimmer.sql.TypedTuple; a data class is fine in Kotlin
data class StoreStatistics(val storeId: Long, val bookCount: Long, val avgPrice: BigDecimal?)

val stats: List<StoreStatistics> = sqlClient.createQuery(Book::class) {
    where(table.storeId.isNotNull())
    groupBy(table.storeId)
    select(
        StoreStatisticsMapper                 // generated
            .storeId(table.storeId.asNonNull())
            .bookCount(rowCount())
            .avgPrice(avgAsDecimal(table.price))
    )
}.execute()
```

Kotlin nullability is enforced: a nullable expression (`table.storeId`, `max(...)`) must be converted with
`.asNonNull()` to feed a non-null slot.

## Aggregation and grouping

```kotlin
count(table)                    // count(id)
count(table.id, distinct = true)
rowCount()                      // count(1)
sum(table.price)  min(table.price)  max(table.price)       // nullable results
avg(table.price)                // Double
avgAsDecimal(table.price)       // BigDecimal — use for money

sqlClient.createQuery(Book::class) {
    groupBy(table.storeId)
    having(avgAsDecimal(table.price).asNonNull() gt BigDecimal(50))
    select(table.storeId, avgAsDecimal(table.price))
}
```

## Expressions

```kotlin
table.price + BigDecimal.ONE    table.price - x    table.price * x    table.price / x    table.price % x
concat(table.firstName, value(" "), table.lastName)
table.name.lower()  .upper()  .trim()  .left(3)  .substring(1, 3)  .position("SQL")
table.website.coalesce("N/A")                       // builder: table.website.coalesce().or(table.name).end()
case().match(table.price lt BigDecimal(30), "Cheap").match(table.price gt BigDecimal(70), "Expensive").otherwise("Normal")
case(table.name).match("SQL in Action", "Matched").otherwise("Other")
value("x")   value(3L)   constant(1)               // constant() inlines a literal (number, enum or string); prefer value()
nullValue(Boolean::class)
```

## Native SQL fragments

```kotlin
sql(Int::class, "rank() over(order by %e desc)") { expression(table.price) }
sql(Boolean::class, "regexp_like(%e, %v)") { expression(table.name); value("^SQL") }   // %e expression, %v value
sqlNullable(String::class, "json_value(%e, '$.x')") { expression(table.name) }
```

## Ordering and paging

```kotlin
orderBy(table.name)                                  // asc by default
orderBy(table.edition.desc(), table.price.desc())    // keep all args as orders (asc()/desc()) when mixing directions
orderBy(table.makeOrders("store.name asc, name, edition desc"))   // client-specified sort
orderBy(sort)                                       // Spring Data Sort: import org.babyfish.jimmer.spring.repository.orderBy (starter)

val page: Page<Book> = sqlClient.createQuery(Book::class) {
    orderBy(table.name.asc(), table.edition.desc())
    select(table.fetch(fetcher))
}.fetchPage(0, 10)
```

## Locking, hints, set operations

```kotlin
sqlClient.createQuery(Book::class) { select(table) }.forUpdate()
q.forUpdate(LockMode.SHARE, LockWait.NO_WAIT)
q.hint("/*+ ... */")
val ids = q1 union q2        // unionAll, minus, intersect (infix)
```

## Association (middle) table queries

```kotlin
import org.babyfish.jimmer.sql.kt.ast.table.source
import org.babyfish.jimmer.sql.kt.ast.table.target

val rows: List<Association<Book, Author>> = sqlClient.queries.forList(Book::authors) {
    where(table.target.firstName eq "Alex")
    select(table)
}.execute()
// sqlClient.queries.forReference(Book::store) { ... } for join-table many-to-one; rows[0].source() / target()
```

## Weak joins

```kotlin
class BookAuthorJoin : KWeakJoin<Book, Author>() {         // top-level class with a no-arg constructor
    override fun on(source: KNonNullTable<Book>, target: KNonNullTable<Author>): KNonNullExpression<Boolean> =
        source.name eq target.lastName
}

sqlClient.createQuery(Book::class) {
    where(table.asTableEx().weakJoin(BookAuthorJoin::class).firstName eq "Alex")
    select(table.id)
}.distinct().execute()

// lambda form: table.asTableEx().weakJoin(Author::class) { source.name eq target.lastName }
```

## Base queries: derived tables, CTE, recursive CTE

```kotlin
import org.babyfish.jimmer.sql.kt.ast.query.baseTableSymbol
import org.babyfish.jimmer.sql.kt.ast.query.cteBaseTableSymbol

val ranked = baseTableSymbol {
    sqlClient.createBaseQuery(BookStore::class) {
        selections
            .add(table)
            .add(sql(Int::class, "dense_rank() over(order by %e desc)") {
                expression(subQuery(Book::class) {
                    where(table.storeId eq parentTable.id)
                    select(rowCount())
                })
            })
    }
}
val top: List<BookStore> = sqlClient.createQuery(ranked) {
    where(table._2 le 2)
    select(table._1.fetchBy { allScalarFields() })      // shape in the OUTER query
}.execute()

// recursive CTE (union all only)
val tree = cteBaseTableSymbol {
    sqlClient.createBaseQuery(TreeNode::class) {
        where(table.parentId.isNull())
        selections.add(table).add(constant(1))
    }.unionAllRecursively {
        sqlClient.createBaseQuery(TreeNode::class, it, { source.parentId eq target._1.id }) {
            selections.add(table).add(recursive._2 + 1)
        }
    }
}
sqlClient.createQuery(tree) {
    orderBy(table._2, table._1.name)
    select(table._1.fetchBy { name() }, table._2)
}.execute()
```

A typed-tuple mapper in `select(...)` of a base query yields a named base table; `createBaseQuery()` without an
entity builds constant rows (useful for insert-from-select).

## Inheritance queries

```kotlin
sqlClient.createQuery(Client::class) {
    where(table.asTableEx().instanceOf(Organization::class))      // also exactType(...)
    select(table.id, table.asTableEx().treatAs(Organization::class).taxCode)   // tryTreatAs = nullable
}
sqlClient.createQuery(Organization::class) {
    typeMatchMode(TypeMatchMode.EXACT)                              // EXACT on an abstract root throws
    select(table)
}
```

`treatAs`/`instanceOf` are generated on `KTableEx<Root>` — call `asTableEx()` first. Fetch subtype props with
`newFetcher(Client::class).by { name(); forType(Organization::class) { taxCode() } }` or a DTO.
