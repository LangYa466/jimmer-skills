# Delete commands, DML statements and association tables (Kotlin)

## Contents
- [Choosing the right tool](#choosing-the-right-tool)
- [Delete commands (by id)](#delete-commands-by-id)
- [Dissociation during delete](#dissociation-during-delete)
- [Update statements](#update-statements)
- [Delete statements](#delete-statements)
- [Insert / upsert from a query](#insert--upsert-from-a-query)
- [Association (middle) tables](#association-middle-tables)

## Choosing the right tool

| Need | API |
|---|---|
| Save an object graph | save command |
| Delete by id honoring logical deletion and `@OnDissociate` | `deleteById` / `deleteByIds` |
| Set columns on rows matching a predicate | `createUpdate(Book::class) { ... }` / `executeUpdate` |
| Delete rows matching a predicate | `createDelete(Book::class) { ... }` / `executeDelete` |
| Bulk insert/upsert rows produced by a query | `createInsert` / `createUpsert` |
| Add/remove middle-table rows by ids | `getAssociations(Book::authors)` |

DML statements work on columns: `createUpdate`/`createDelete` never run draft interceptors (set audit columns
yourself); map every required column in `createInsert`/`createUpsert`. Triggers and cache invalidation still work.

## Delete commands (by id)

```kotlin
val r1: KDeleteResult = sqlClient.deleteById(Book::class, 1L)              // DeleteMode.AUTO
sqlClient.deleteByIds(Book::class, listOf(2L, 3L))
sqlClient.deleteById(Author::class, 3L, DeleteMode.PHYSICAL)               // even if logically deletable
sqlClient.deleteById<Book>(4L)                                             // reified form

val r = sqlClient.deleteById(BookStore::class, 1L) {                       // KDeleteCommandDsl
    setDissociateAction(Book::store, DissociateAction.SET_NULL)
    setMode(DeleteMode.PHYSICAL)
}
r.totalAffectedRowCount
r.affectedRowCount(BookStore::class)
r.affectedRowCount(Book::authors)                                          // middle-table rows
```

| DeleteMode | Entity with `@LogicalDeleted` | Without |
|---|---|---|
| `AUTO` (default) | logical (`UPDATE`) | physical |
| `LOGICAL` | logical | exception |
| `PHYSICAL` | physical | physical |

Deleting a missing id is not an error (0 rows).

## Dissociation during delete

Physical deletion of a parent first handles children: middle-table rows are deleted; FK children follow the
many-to-one's `@OnDissociate` (`CHECK` by default → `SaveException.CannotDissociateTarget`, `SET_NULL`, `DELETE`
recursive, `LAX`). `@JoinTable(cascadeDeletedBySource = true)` means the DB cascades middle rows itself.

## Update statements

```kotlin
val count = sqlClient.createUpdate(Author::class) {
    set(table.firstName, concat(table.firstName, value("*")))   // expression
    set(table.gender, Gender.MALE)                               // value
    where(table.firstName eq "Dan")
}.execute()

sqlClient.executeUpdate(Author::class) {                         // create + execute
    set(table.lastName, "X")
    where(table.id eq 3L)
}

val rows: List<Tuple2<Long, String>> = sqlClient.createUpdateReturning(Author::class) {   // H2 / Postgres only
    set(table.firstName, "Daniel")
    where(table.firstName eq "Dan*")
    returning(table.id, table.firstName)                         // the lambda returns the executable
}.execute()
```

- Global filters (and logical deletion) apply to the `where`; use
  `sqlClient.filters { setBehavior(LogicalDeletedBehavior.IGNORED) }` / `filters { disableByTypes(...) }` to touch
  filtered rows. (`KFilterDsl` has no `disableAll()`.)
- Joins in updates are supported only by `MySqlDialect`/`PostgresDialect`; use subqueries elsewhere.

## Delete statements

```kotlin
sqlClient.createDelete(Book::class) {
    where(table.name eq "Learning GraphQL")
}.execute()                                     // returns rows of ALL touched tables (middle-table rows included)

sqlClient.executeDelete(Book::class) {
    where(table.store.name eq "MANNING")        // join → select ids + delete command
    setMode(DeleteMode.PHYSICAL)
}

sqlClient.executeDelete(Book::class) {
    where(table.edition gt 5)
    disableDissociation()                       // plain DELETE, no child handling
}
```

## Insert / upsert from a query

```kotlin
@TypedTuple
data class CourseRow(val id: Long, val name: String)

val source = baseTableSymbol {                  // rootless base query: constant row(s)
    sqlClient.createBaseQuery {
        select(CourseRowMapper.id(value(100L)).name(value("Math")))
    }
}

val inserted: Int = sqlClient.createInsert(Course::class, source) {
    set(table.id, sourceTable.id)
    set(table.name, sourceTable.name)
    onConflictDoNothing(table.id)               // or onConflictDoNothing() to infer id/key
}.execute()

val upserted: List<Tuple2<Long, String>> = sqlClient.createUpsertReturning(Course::class, source) {
    key(table.id, sourceTable.id)
    merge(table.name, sourceTable.name)         // insert(...) insert-only, update(...) update-only, merge(t, ins, upd)
    updateWhere(table.name ne sourceTable.name)
    returning(table.id, table.name)
}.execute()

// entity query as source
val stores = baseTableSymbol {
    sqlClient.createBaseQuery(BookStore::class) {
        where(table.name eq "MANNING")
        select(table)
    }
}
sqlClient.createInsert(Course::class, stores) {
    set(table.id, sourceTable.id)
    set(table.name, sourceTable.name)
}.execute()

// middle table (extensions sourceId/targetId from org.babyfish.jimmer.sql.kt.ast.table); rootless source only in 0.12.4
sqlClient.createInsert(Book::authors, pairs) {
    set(table.sourceId, sourceTable.bookId)
    set(table.targetId, sourceTable.authorId)
    onConflictDoNothing()
}.execute()
```

Upserted versions follow assignment semantics (no implicit optimistic lock). `executeInsert`/`executeUpsert`
variants run immediately. Entity targets must be one physical table (no `JOINED` targets).

## Association (middle) tables

```kotlin
val bookAuthors = sqlClient.getAssociations(Book::authors)
bookAuthors.insert(10L, 3L)                       // fails on duplicates
bookAuthors.insertIfAbsent(10L, 3L)
bookAuthors.insertAll(listOf(10L, 11L), listOf(1L, 3L))          // cartesian product
bookAuthors.replace(10L, 3L)                      // insertIfAbsent + delete other targets of source 10
bookAuthors.delete(10L, 3L)
bookAuthors.deleteAll(listOf(10L, 11L), listOf(1L, 3L))
val authorBooks = bookAuthors.reverse()           // == getAssociations(Author::books); argument order flips
```

To set a collection exactly, prefer a save command with id-only targets:
`sqlClient.save(Book { id = 10L; authorIds = listOf(1L, 3L) }, SaveMode.UPDATE_ONLY, AssociatedSaveMode.REPLACE)`.
