# Delete commands, DML statements and association tables (Java)

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
| Save an object or a whole object graph | save command (see `references/save-command.md`) |
| Delete objects by id, honoring logical deletion and `@OnDissociate` | `deleteById` / `deleteByIds` / `getEntities().deleteCommand(...)` |
| Set columns on all rows matching a predicate | `createUpdate(table)` |
| Delete all rows matching a predicate | `createDelete(table)` |
| Copy/import rows produced by a query, bulk upsert with SQL expressions | `createInsert(target, source)` / `createUpsert(target, source)` |
| Add/remove middle-table rows by id pairs | `getAssociations(BookProps.AUTHORS)` |

DML statements work on columns, not drafts: `createUpdate`/`createDelete` never run `DraftInterceptor`s (set audit
columns such as `modifiedTime` yourself), and for `createInsert`/`createUpsert` map every required column explicitly
instead of relying on interceptors. Transaction triggers and cache invalidation still work for all of them.

## Delete commands (by id)

```java
DeleteResult r1 = sqlClient.deleteById(Book.class, 1L);                          // DeleteMode.AUTO
DeleteResult r2 = sqlClient.deleteByIds(Book.class, List.of(1L, 2L));
DeleteResult r3 = sqlClient.deleteById(Author.class, 3L, DeleteMode.PHYSICAL);   // even if logically deletable

DeleteResult r4 = sqlClient.getEntities()
        .deleteCommand(BookStore.class, 1L)
        .setDissociateAction(BookProps.STORE, DissociateAction.SET_NULL)         // override @OnDissociate
        .execute();
sqlClient.getEntities().deleteAll(Book.class, List.of(3L, 4L), DeleteMode.AUTO);
sqlClient.getEntities().deleteAllCommand(Book.class, List.of(5L)).setMode(DeleteMode.PHYSICAL).execute();

r4.getTotalAffectedRowCount();
r4.getAffectedRowCount(AffectedTable.of(Book.class));
r4.getAffectedRowCount(AffectedTable.of(BookProps.AUTHORS));                     // middle-table rows
r4.getAffectedRowCount(BookStore.class);
```

| DeleteMode | Entity has `@LogicalDeleted` | Entity without it |
|---|---|---|
| `AUTO` (default) | logical delete (`UPDATE ... SET DELETED = true`) | physical delete |
| `LOGICAL` | logical delete | exception |
| `PHYSICAL` | physical delete | physical delete |

`DeleteCommand` options: `setMode`, `setDissociateAction(manyToOneProp, action)`, `setTypeMatchMode`,
`setMaxCommandJoinCount`, `setDumbBatchAcceptable()`, `setTransactionRequired`. There is no `configure(...)` in 0.12.4
(older docs show one). Deleting a non-existing id is not an error (affected count 0).

Spring repositories provide `deleteById(id[, DeleteMode])`, `deleteByIds(ids[, DeleteMode])` and `deleteAllById`.

## Dissociation during delete

Physically deleting a parent first handles its children:

- Middle-table associations (`Book.authors`): the middle-table rows are deleted.
- One-to-many through a FK (`BookStore.books`): Jimmer queries the children and applies the `@OnDissociate` action of
  the many-to-one property (`Book.store`) or the command override:
  - `NONE` → `CHECK` by default (or `LAX` for fake FKs when `setDefaultDissociateActionCheckable(false)`)
  - `CHECK` → throw if children exist
  - `LAX` → do nothing (rely on `ON DELETE CASCADE`/no FK)
  - `SET_NULL` → null out child FKs
  - `DELETE` → recursively delete children (and their children, applying their own rules)
- `@JoinTable(cascadeDeletedBySource = true / cascadeDeletedByTarget = true)` tells Jimmer the DB already cascades
  middle-table rows (`ON DELETE CASCADE`), so it skips deleting them.

Logical deletion of a parent logically deletes `DELETE`-configured children that are also logically deletable.

## Update statements

```java
AuthorTable author = AuthorTable.$;
int count = sqlClient.createUpdate(author)
        .set(author.firstName(), author.firstName().concat("*"))     // expression
        .set(author.gender(), Gender.MALE)                             // value
        .where(author.firstName().eq("Dan"))
        .execute();

List<Tuple2<Long, String>> rows = sqlClient.createUpdate(author)  // H2 and Postgres only
        .set(author.firstName(), "Daniel")
        .where(author.firstName().eq("Dan*"))
        .returning(author.id(), author.firstName())
        .execute();
try (Stream<Tuple2<Long, String>> s = sqlClient.createUpdate(author)
        .set(author.firstName(), "Dan")
        .where(author.firstName().eq("Daniel"))
        .returning(author.id(), author.firstName())
        .stream()) {
    s.forEach(System.out::println);
}
```

- Global filters (including the logical-deletion filter) are applied to the `where` clause, so filtered rows are not
  updated. To touch them, run the statement on a reconfigured client:
  `sqlClient.filters(cfg -> cfg.disableAll())` disables **user** filters only; logically deleted rows additionally
  need `sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED))`.
- `whereIf(condition, () -> predicate)` adds a predicate conditionally.
- Joins in updates (e.g. `authorEx.books().store().name().eq(...)`) are only supported by `MySqlDialect` and
  `PostgresDialect` (Postgres: first-level joins must be inner). Elsewhere use a subquery in `where`.
- `returning(...)` accepts physical columns of the updated table (or a typed-tuple mapper) and is only supported by
  `H2Dialect` and `PostgresDialect`.

## Delete statements

```java
BookTable book = BookTable.$;
int n = sqlClient.createDelete(book)
        .where(book.name().eq("Learning GraphQL"))
        .execute();

sqlClient.createDelete(book)
        .where(book.store().name().eq("MANNING"))   // join → executed as select ids + delete command
        .setMode(DeleteMode.PHYSICAL)
        .execute();

sqlClient.createDelete(book).where(book.edition().gt(5)).disableDissociation().execute(); // plain DELETE, no child handling
```

`execute()` returns the total affected rows of **all** tables touched (middle-table rows included).
`createDelete` respects logical deletion (`AUTO` → `UPDATE ... SET DELETED`) and runs the same child/middle-table
dissociation as delete commands. `disableDissociation()` skips that and issues a plain `DELETE` (use it only when the
database cascades or no children can exist).

## Insert / upsert from a query

`createInsert` / `createUpsert` write rows supplied by a **base query** (entity table, typed tuple, CTE, union…)
without materializing entities in Java. Prefer native `INSERT ... SELECT` / `MERGE` / `ON CONFLICT`; Jimmer falls back to
materializing rows through the save pipeline when the dialect can't express the request.

```java
// a typed-tuple source row (class, not record)
@TypedTuple
public class CourseRow {
    private final long id;
    private final String name;
    public CourseRow(long id, String name) { this.id = id; this.name = name; }
    public long getId() { return id; }
    public String getName() { return name; }
}

CourseRowTable source = sqlClient.createBaseQuery()                       // rootless base query: constant row
        .select(CourseRowMapper.id(Expression.value(100L)).name(Expression.value("Math")))
        .asBaseTable();

CourseTable course = CourseTable.$;
int inserted = sqlClient.createInsert(course, source)
        .set(course.id(), source.getId())
        .set(course.name(), source.getName())
        .onConflictDoNothing(course.id())                  // or onConflictDoNothing() to infer id/key
        .execute();

List<Tuple2<Long, String>> upserted = sqlClient.createUpsert(course, source)
        .key(course.id(), source.getId())                  // conflict key (complete id or key group)
        .insert(course.name(), source.getName())           // insert-only value
        // .merge(col, source)                             // same value for insert and update
        // .merge(col, insertValue, updateExpression)      // different values
        // .update(col, course.version().plus(1))          // update-only expression (may read the target row)
        // .updateWhere(course.name().ne(source.getName()))
        .returning(course.id(), course.name())             // only rows that took an accepted branch
        .execute();
```

- Source from an entity query: `BookStoreTable selected = sqlClient.createBaseQuery(store).where(...).select(store).asBaseTable();`
  then map `selected.id()`, `selected.name()`. Use a separate target table instance (`BookStoreArchiveTable.$`);
  an exported source table cannot be the target.
- Upsert versions follow *assignment* semantics (no implicit optimistic lock, no auto-increment).
- Entity targets must be one physical table (single-table inheritance OK, `JOINED` not supported).
- Middle tables support insert (not upsert). In 0.12.4 the source must be a **rootless** base query
  (`createBaseQuery()` with constant/typed-tuple selections); an entity-rooted source query throws
  `UnsupportedOperationException`. To link pairs computed from entities, query them and call
  `getAssociations(prop).insertAllIfAbsent(tuples)` instead.
  ```java
  AssociationTable<Book, BookTableEx, Author, AuthorTableEx> assoc =
          AssociationTable.of(BookTableEx.class, BookTableEx::authors);
  sqlClient.createInsert(assoc, pairs)
          .set(assoc.<Long>sourceId(), pairs.getBookId())
          .set(assoc.<Long>targetId(), pairs.getAuthorId())
          .onConflictDoNothing()
          .execute();
  ```

## Association (middle) tables

For simple incremental link changes without loading entities:

```java
Associations bookAuthors = sqlClient.getAssociations(BookProps.AUTHORS);
bookAuthors.insert(10L, 3L);                       // fails on duplicates
bookAuthors.insertIfAbsent(10L, 3L);               // ignores existing pairs
bookAuthors.insertAll(List.of(10L, 11L), List.of(1L, 3L));          // cartesian product
bookAuthors.insertAllIfAbsent(List.of(new Tuple2<>(10L, 1L), new Tuple2<>(11L, 3L)));
bookAuthors.replace(10L, 3L);                      // insertIfAbsent + delete other targets of source 10
bookAuthors.delete(10L, 3L);
bookAuthors.deleteAll(List.of(10L, 11L), List.of(1L, 3L));
bookAuthors.saveAllCommand(List.of(10L), List.of(1L, 2L)).ignoreConflict(true).execute();

Associations authorBooks = bookAuthors.reverse();  // == getAssociations(AuthorProps.BOOKS); argument order flips
```

The old `checkExistence()` method is now `ignoreConflict(true)`. When you need "make the collection exactly X",
use a save command with id-only targets instead:

```java
sqlClient.saveCommand(Immutables.createBook(d -> d.setId(10L).setAuthorIds(List.of(1L, 3L))))
        .setMode(SaveMode.UPDATE_ONLY)
        .execute();           // AssociatedSaveMode.REPLACE: adds missing links, removes the others
```

Query middle tables with `sqlClient.createQuery(AssociationTable.of(BookTableEx.class, BookTableEx::authors))`
(see `references/query-dsl.md`).
