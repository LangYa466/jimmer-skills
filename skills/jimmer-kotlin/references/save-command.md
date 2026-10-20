# Save commands (Kotlin)

## Contents
- [Entry points and their defaults](#entry-points-and-their-defaults)
- [The shape of the object is the instruction](#the-shape-of-the-object-is-the-instruction)
- [Root save mode](#root-save-mode)
- [Associated save mode](#associated-save-mode)
- [How objects are matched: id, key, key groups](#how-objects-are-matched-id-key-key-groups)
- [Dissociation and child transfer](#dissociation-and-child-transfer)
- [Results, acceptance and re-fetching](#results-acceptance-and-re-fetching)
- [Controlling the update: mask, condition, assignment, version mode](#controlling-the-update-mask-condition-assignment-version-mode)
- [Optimistic and pessimistic locking](#optimistic-and-pessimistic-locking)
- [Saving input DTOs](#saving-input-dtos)
- [Errors and exception translation](#errors-and-exception-translation)
- [Batching, MySQL, transactions, global defaults](#batching-mysql-transactions-global-defaults)
- [DSL cheat sheet](#dsl-cheat-sheet)

## Entry points and their defaults

A save command takes an arbitrarily deep entity graph, compares it with the database and issues batched
INSERT/UPDATE/DELETE statements.

| Call | Root mode | **Association mode** |
|---|---|---|
| `sqlClient.save(entity)` / `save(entity) { ... }` | `UPSERT` | `REPLACE` |
| `sqlClient.save(entity, SaveMode.X)` | `X` | **`MERGE`** (default parameter) |
| `sqlClient.save(entity, SaveMode.X, AssociatedSaveMode.Y)` | `X` | `Y` |
| `sqlClient.saveCommand(entity, SaveMode.X)` | `X` | **`REPLACE`** (default parameter) |
| `saveEntities(list[, mode[, assocMode]])`, `save(input...)`, `saveInputs(...)` | same rules as above | |
| repository `save(entity, SaveMode.X)` (`KotlinRepository`) | `X` | `REPLACE` |

The `save(entity, mode)` / `saveCommand(entity, mode)` defaults differ — pass the `AssociatedSaveMode` explicitly
whenever associations are loaded. There is no `insert`/`update`/`merge` on `KSqlClient` (repositories still have them, all `@Deprecated`).

```kotlin
sqlClient.save(book)                                                    // UPSERT + REPLACE
sqlClient.save(book, SaveMode.INSERT_ONLY, AssociatedSaveMode.APPEND)   // "insert"
sqlClient.save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.UPDATE)   // "update"
sqlClient.save(book) {                                                  // full DSL (KSaveCommandDsl)
    setMode(SaveMode.UPSERT)
    setAssociatedModeAll(AssociatedSaveMode.MERGE)
    setAssociatedMode(Book::authors, AssociatedSaveMode.APPEND)         // per association (wins over *All)
}
val cmd = sqlClient.saveCommand(book) { setMode(SaveMode.UPDATE_ONLY) } // build, then execute(...)
```

When the mode is passed as an argument, the lambda is a `KSaveCommandPartialDsl` (no `setMode`/`setAssociatedModeAll`).

## The shape of the object is the instruction

- **Unloaded property** → column untouched; **`null`** → column set to NULL:
  `sqlClient.save(Book { id = 8L; store = null }, SaveMode.UPDATE_ONLY)` only clears `STORE_ID`.
- Unloaded association → untouched; loaded association → saved according to its `AssociatedSaveMode`.
- Associated object shape: **id-only** = link only; **key-only** + `setKeyOnlyAsReference(...)` = link by key;
  anything else = the target is saved too (recursively).
- Classification: id-specified (id loaded) / key-specified (complete `@Key` group) / wild (neither → rejected with
  `SaveException.NeitherIdNorKey` unless `INSERT_ONLY` or `NON_IDEMPOTENT_UPSERT`; `INSERT_IF_ABSENT` rejects them too).
- Forward associations (FK on this table) are saved before the owner, backward ones (one-to-many, many-to-many)
  after it. Both sides of a bidirectional association can be saved.
- Children saved through a `mappedBy` list get their back reference automatically; the **root** of a structure keyed by
  `(name, parent)` must still set `parent` explicitly: `input.toEntity().copy { parent = null }`.

## Root save mode

| SaveMode | Behavior |
|---|---|
| `UPSERT` (default) | insert or update by id/key; native upsert where possible, else select + insert/update |
| `INSERT_ONLY` | unconditional insert |
| `INSERT_IF_ABSENT` | insert or skip an existing row (`isAccepted == false`) |
| `UPDATE_ONLY` | update by id or key; missing rows are simply not updated |
| `NON_IDEMPOTENT_UPSERT` | wild objects inserted, others upserted (not recommended) |

When Jimmer must select first, the SQL log shows a `QueryReason` (`KEY_UNIQUE_CONSTRAINT_REQUIRED`,
`NULL_NOT_DISTINCT_REQUIRED`, `OPTIMISTIC_LOCK`, `INTERCEPTOR`, `TRIGGER`, …). Declare `@KeyUniqueConstraint` when a
real unique constraint backs the key.

## Associated save mode

| AssociatedSaveMode | Applies to | Wild targets | Behavior |
|---|---|---|---|
| `REPLACE` | backward associations | no | `MERGE` + dissociate missing targets |
| `MERGE` | all | no | insert or update targets, never removes links |
| `APPEND` | all | yes | insert targets |
| `APPEND_IF_ABSENT` | all | no | insert missing targets, keep existing ones |
| `UPDATE` | all | no | update targets |
| `VIOLENTLY_REPLACE` | backward associations | yes | delete all old, re-insert (slow; avoid) |

Id-only targets are references; `setIdOnlyAsReference(Book::authors, false)` /
`setIdOnlyAsReferenceAll(false)` treats them as entities. Existence of id-only targets is not checked by default;
enable with `setAutoIdOnlyTargetChecking(Book::authors)` / `setAutoIdOnlyTargetCheckingAll()` or the global
`setIdOnlyTargetCheckingLevel(...)` → `SaveException.IllegalTargetId`.

```kotlin
sqlClient.save(Book {
    name = "Book"; edition = 1; price = BigDecimal.TEN
    authors().addBy { firstName = "Alex"; lastName = "Banks" }     // key of Author
}) {
    setKeyOnlyAsReference(Book::authors)                           // look up by key, never insert/modify Author
}
```

## How objects are matched: id, key, key groups

- `@Key` props (scalars and/or many-to-one); `@Key(group = "...")` for several keys — Jimmer picks the fully loaded
  group with the most properties.
- Runtime override: `setKeyProps(Book::name, Book::edition)` or `setKeyProps("group", ...)`.
- `matchByKey()`: match roots by key even when an id is supplied (the id is only used for inserts).

## Dissociation and child transfer

With `REPLACE`, targets missing from the new collection are dissociated: middle-table rows are deleted; FK children
follow `@OnDissociate` of the many-to-one (`Book.store`) or the override:

| DissociateAction | Effect |
|---|---|
| `NONE` (default) | `CHECK`, or `LAX` when `setDefaultDissociateActionChecking(false)` (real and fake FKs alike) |
| `LAX` | nothing (treated as `CHECK` under `REPLACE`) |
| `CHECK` | throw `SaveException.CannotDissociateTarget` |
| `SET_NULL` | clear the child FK (prop must be nullable) |
| `DELETE` | delete children recursively; logically only when enabled (per command `setEnabled(true)`, globally `setDissociationLogicalDeleteEnabled(true)`), with a fake FK, or under a logically deleted parent |

```kotlin
sqlClient.save(store) {
    setDissociateAction(Book::store, DissociateAction.DELETE)
    setTargetTransferMode(BookStore::books, TargetTransferMode.ALLOWED)   // allow taking books from other stores
}
```

Taking a child that belongs to another parent throws `SaveException.TargetIsNotTransferable` unless
`setTargetTransferMode(...)`/`setTargetTransferModeAll(...)` or the global `setTargetTransferable(true)`.

## Results, acceptance and re-fetching

```kotlin
val r: KSimpleSaveResult<Book> = sqlClient.save(book)
r.modifiedEntity           // generated ids, versions, back references
r.originalEntity
r.isModified               // reference comparison only
r.isAccepted               // false: INSERT_IF_ABSENT skip, setUpdateWhere rejection, incompatible subtype
r.totalAffectedRowCount
r.affectedRowCount(Book::class)
r.affectedRowCount(Book::authors)                     // middle-table rows
r.affectedRowCountMap
val items = sqlClient.saveEntities(books).items       // per-object results

val saved = sqlClient.saveCommand(book).execute(newFetcher(Book::class).by { allScalarFields(); store { name() } }).modifiedEntity
val view: BookView = sqlClient.saveCommand(book).execute(BookView::class).modifiedView
```

`setSaveResultReadsAllProperties(true)` re-reads written values (DB triggers/generated columns);
`setSaveReturningEnabled(false)` disables DML returning (built in for H2/Postgres).

## Controlling the update: mask, condition, assignment, version mode

```kotlin
sqlClient.save(book) { setUpsertMask(Book::price) }                     // on conflict update only PRICE

sqlClient.save(book) {
    setUpsertMask(
        UpsertMask.of(Book::class.java)                                  // org.babyfish.jimmer.sql.ast.mutation.UpsertMask
            .addInsertableProp(Book::name).addInsertableProp(Book::edition).addInsertableProp(Book::price)
            .addUpdatableProp(Book::price)                               // Kotlin extensions in ...kt.ast.mutation
    )
}

sqlClient.save(book) { forbidUpdate() }                                  // accept existing rows, update nothing

val r = sqlClient.save(book) {
    setUpdateWhere(Book::class) { table.price lt newNonNull(Book::price) }   // column on the LEFT (see warning)
}
if (!r.isAccepted) { /* rejected, not an error */ }

sqlClient.save(Book { id = 1L; price = BigDecimal("5") }, SaveMode.UPDATE_ONLY) {
    set(Book::price) { target.price + newNonNull(Book::price) }          // SQL-computed assignment
    // setNullable(BookStore::website) { ... } for nullable scalar targets; newNullable(prop) for nullable inputs
}

sqlClient.save(store) {
    setVersionMode(VersionMode.ASSIGNMENT)                                // @Version as a plain value
    setUpdateWhere(BookStore::class) { table.version lt newNonNull(BookStore::version) }
}
```

> **Keep the column on the left.** In 0.12.4 a comparison whose left side is a `value(...)` literal is rendered with
> the *negated* operator (`value(x) gt col` → `col <= x`). Kotlin's `newNonNull(...)`/`newNullable(...)` are not
> affected (verified), but writing `table.p lt newNonNull(P)` is the safe habit — and never use `value(x)` on the left.

Rules: `table`/`target` = stored row (local columns, no joins); `newNonNull(prop)`/`newNullable(prop)` = incoming
value (must be loaded). `set` only replaces assignments of loaded, mask-allowed props; never id, discriminator,
logical-deleted flag, associations, formulas (version only with `ASSIGNMENT`). Not applied to inserts. A command
with `setUpdateWhere` rejects mutable forward-reference objects — use id-only references.

## Optimistic and pessimistic locking

```kotlin
// @Version val version: Int — a loaded, stale version throws SaveException.OptimisticLockError
sqlClient.save(BookStore { id = 1L; website = "w"; version = 3 }, SaveMode.UPDATE_ONLY)

sqlClient.saveEntities(stores, SaveMode.UPDATE_ONLY) {
    setOptimisticLock(BookStore::class) {                                      // custom predicate, exception if false
        sql(Boolean::class, "coalesce(length(%e), 0) <= length(%e)") {
            expression(table.website)
            expression(newNullable(BookStore::website))
        }
    }
}
sqlClient.save(store) {
    setOptimisticLock(BookStore::class, UnloadedVersionBehavior.INCREASE) {  // bump VERSION even when unloaded
        table.name eq newNonNull(BookStore::name)
    }
}

sqlClient.transaction {
    sqlClient.saveEntities(books) { setPessimisticLock(Book::class) }        // SELECT ... FOR UPDATE on pre-queries
    sqlClient.save(store) { setPessimisticLockAll() }
}
```

A loaded `@Version` (or `setOptimisticLock`) makes upserts select first (reason `OPTIMISTIC_LOCK`), except on dialects
that support upsert with an optimistic-lock check (`PostgresDialect`).

## Saving input DTOs

```kotlin
sqlClient.save(input)                                   // = save(input.toEntity())
sqlClient.saveInputs(listOf(in1, in2))
sqlClient.save(input, SaveMode.UPDATE_ONLY, AssociatedSaveMode.REPLACE)
sqlClient.save(treeInput.toEntity().copy { parent = null })   // recursive roots keyed by (name, parent)
```

`id(store)` / `id(authors) as authorIds` in the DTO become id-only references; `dynamic`/`fuzzy` inputs give PATCH
semantics (`references/dto-language.md`).

## Errors and exception translation

`SaveException` subclasses (`org.babyfish.jimmer.sql.exception`): `NeitherIdNorKey`, `NotUnique`,
`OptimisticLockError`, `CannotDissociateTarget`, `TargetIsNotTransferable`, `IllegalTargetId`, `NullTarget`,
`NoIdGenerator`, `IllegalIdGenerator`, `IllegalGeneratedId`, `NoKeyProp`, `NoVersion`, `IncompleteProperty`,
`ReadonlyMiddleTable`, `UnstructuredAssociation`, remote-association errors, `IllegalInterceptorBehavior`.
Each has `path`/`exportedPath` and `saveErrorCode`.

```kotlin
sqlClient.save(book) {
    addExceptionTranslator(object : ExceptionTranslator<SaveException.NotUnique> {     // NOT a SAM lambda
        override fun translate(exception: SaveException.NotUnique, args: ExceptionTranslator.Args?): Exception? =
            if (exception.isMatched(BookProps.NAME, BookProps.EDITION)) {
                IllegalArgumentException("Book ${exception.getValue(BookProps.NAME)} edition ${exception.getValue(BookProps.EDITION)} already exists")
            } else {
                null                                      // not handled
            }
    })
}
```

Jimmer reads the translator's generic type argument by reflection: a Kotlin SAM lambda
(`ExceptionTranslator<SaveException.NotUnique> { e, _ -> ... }`) loses it and fails with "Illegal exception translator
type ... that type is not class type". Use an `object :` expression or a named class.

Constraint violations are investigated and translated to `NotUnique` by default
(`setConstraintViolationTranslatable(false)` to disable). Register translators globally with
`addExceptionTranslator` in `newKSqlClient { }` or as Spring beans.

## Batching, MySQL, transactions, global defaults

- Saves are batched per type and shape. MySQL batches need `rewriteBatchedStatements=true`, `setExplicitBatchEnabled(true)`
  and dumb-batch acceptance: per command `setDumbBatchAcceptable(true)` (preferred) or globally `setAcceptable(true)` in
  `newKSqlClient { }` (the Kotlin name of Java's `setDumbBatchAcceptable`).
- Run graph saves inside `sqlClient.transaction { }` or Spring `@Transactional`; `setTransactionRequired(true)` /
  global `setMutationTransactionRequired(true)` fail fast without a transaction.
- Global (`newKSqlClient { }` / Spring properties): `setTargetTransferable` (false), `setDefaultDissociateActionChecking`
  (true), `setIdOnlyTargetCheckingLevel` (NONE), `setDissociationLogicalDeleteEnabled` (false), `setDefaultSaveReturningEnabled`
  (true), `setConstraintViolationTranslatable` (true), `setMaxCommandJoinCount` (2).

## DSL cheat sheet

`setMode`, `setAssociatedModeAll`, `setAssociatedMode(prop, mode)`, `setKeyProps(...)`, `matchByKey()`,
`setUpsertMask(...)`, `forbidUpdate()`, `setUpdateWhere(Type::class) { }`, `set(prop) { }`, `setNullable(prop) { }`,
`setVersionMode`, `setOptimisticLock(Type::class[, behavior]) { }`, `setPessimisticLock(Type::class)`,
`setPessimisticLockAll()`, `setDissociateAction(prop, action)`, `setEnabled(bool)` (dissociation logical delete),
`setTargetTransferMode(prop, mode)`, `setTargetTransferModeAll`, `setIdOnlyAsReference[All]`,
`setKeyOnlyAsReference[All]`, `setAutoIdOnlyTargetChecking[All]`, `setDeleteMode`, `setTypeMatchMode`,
`setTypeChangeAllowed`, `setSaveReturningEnabled`, `setSaveResultReadsAllProperties`,
`setConstraintViolationTranslatable`, `addExceptionTranslator`, `setDumbBatchAcceptable`, `setMaxCommandJoinCount`,
`setTransactionRequired`; execute with `execute()`, `execute(fetcher)`, `execute(ViewClass::class)`.
