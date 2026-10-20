# Save commands (Java)

## Contents
- [Entry points](#entry-points)
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
- [Inheritance](#inheritance)
- [Batching, MySQL and transactions](#batching-mysql-and-transactions)
- [Global defaults (builder)](#global-defaults-builder)
- [Command option cheat sheet](#command-option-cheat-sheet)

## Entry points

A save command takes an **arbitrarily deep entity graph**, compares it with the database and issues the minimal
batched INSERT/UPDATE/DELETE statements. These are the only save methods on `JSqlClient` in 0.12.4:

| Method | Returns | Notes |
|---|---|---|
| `save(entity)` | `SimpleSaveResult<E>` | `UPSERT` + `REPLACE` |
| `save(entity, SaveMode, AssociatedSaveMode)` | `SimpleSaveResult<E>` | both modes are required in this overload |
| `save(input)` / `save(input, SaveMode, AssociatedSaveMode)` | `SimpleSaveResult<E>` | `input` is a generated input DTO (`Input<E>`) |
| `saveEntities(iterable)` / `saveEntities(iterable, SaveMode, AssociatedSaveMode)` | `BatchSaveResult<E>` | one batched command for many roots |
| `saveInputs(iterable)` / `saveInputs(iterable, SaveMode, AssociatedSaveMode)` | `BatchSaveResult<E>` | |
| `saveCommand(entity or input)` | `SimpleEntitySaveCommand<E>` | configure, then `.execute(...)` |
| `saveEntitiesCommand(iterable)` / `saveInputsCommand(iterable)` | `BatchEntitySaveCommand<E>` | |

The same methods exist on `sqlClient.getEntities()`. There is **no** `insert`, `update`, `merge`,
`insertIfAbsent`, `save(entity, SaveMode)` or `save(entity, AssociatedSaveMode)` on `JSqlClient` in 0.12.4 — many doc
pages and blog posts still show them. Spring repositories (`JavaRepository`, `JRepository`) still have them, but
`insert`, `insertIfAbsent`, `update`, `merge` and `save(entity, AssociatedSaveMode)` are all `@Deprecated` there. With
`JSqlClient` (or a repository), write:

```java
sqlClient.save(book, SaveMode.INSERT_ONLY, AssociatedSaveMode.APPEND);          // "insert"
sqlClient.save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.UPDATE);          // "update"
sqlClient.save(book, SaveMode.INSERT_IF_ABSENT, AssociatedSaveMode.APPEND_IF_ABSENT); // "insertIfAbsent"
sqlClient.save(book, SaveMode.UPSERT, AssociatedSaveMode.MERGE);                // old "merge"
sqlClient.saveCommand(book).setMode(SaveMode.UPDATE_ONLY).execute();           // only one mode changed
```

Commands are immutable builders (`@NewChain`): every `setXxx` returns a new command, so always chain or reassign.

## The shape of the object is the instruction

There is no `cascade` configuration. What gets written is decided by **which properties are loaded**:

- **Unloaded property** → column untouched. `null` → column set to NULL. So a partial object is a partial update:
  ```java
  sqlClient.saveCommand(Immutables.createBook(d -> d.setId(8L).setStore(null)))   // only STORE_ID = null
          .setMode(SaveMode.UPDATE_ONLY).execute();
  ```
- **Association unloaded** → association untouched. **Loaded** → the association is saved according to its
  `AssociatedSaveMode` (default `REPLACE`: the collection becomes exactly what you pass).
- **Associated object shape** decides how deep the save goes:

| Associated object | Meaning |
|---|---|
| **id-only** (`{id: 3}`) — "short association" | only the link changes (FK or middle-table row); the target row is not modified |
| **key-only** + `setKeyOnlyAsReference*` | link by key: Jimmer looks up the id by key, the target is not modified |
| anything else — "long association" | the target object is itself saved (insert/update) and recursion continues |

Object classification used by the docs and error messages:

| Kind | Definition | UPSERT matches by |
|---|---|---|
| id-specified | `id` loaded | id |
| key-specified | id unloaded, all `@Key` props of a group loaded | key |
| wild | neither id nor complete key | — rejected (`NeitherIdNorKey`) unless `INSERT_ONLY` or `NON_IDEMPOTENT_UPSERT` (associations: `APPEND`, `VIOLENTLY_REPLACE`); `INSERT_IF_ABSENT` rejects them too |

Association ordering: **forward** associations (FK on this table, e.g. `Book.store`) are saved *before* the owner;
**backward** associations (one-to-many `BookStore.books`, middle-table `Book.authors`) are saved *after* it.
Bidirectional associations can be saved from either side (`Book.authors` or `Author.books`) — unlike JPA, the
`mappedBy` side works the same.

When saving through a one-to-many (`mappedBy`) collection, child objects get their back reference (`parent`, `store`)
set automatically, so children only need the remaining key properties. The **root** of a recursive structure keyed by
`(name, parent)` must still set `parent` explicitly (`setParent(null)` for a root node).

## Root save mode

`SaveMode` affects **only the root objects**:

| SaveMode | Behavior |
|---|---|
| `UPSERT` (default) | insert or update by id/key; uses native upsert (`merge`/`on conflict`/`on duplicate key`) when possible, otherwise select + insert/update |
| `INSERT_ONLY` | unconditional insert (accepts wild objects) |
| `INSERT_IF_ABSENT` | insert, or ignore the existing row (item `isAccepted() == false`, its fields are not updated) |
| `UPDATE_ONLY` | update by id or key; a non-matching object is simply not updated |
| `NON_IDEMPOTENT_UPSERT` | wild objects → insert, others → UPSERT (JPA `merge`-like; not recommended) |

When Jimmer must fall back to "select first", the SQL log shows `purpose` with a `QueryReason` (e.g.
`KEY_UNIQUE_CONSTRAINT_REQUIRED`, `NULL_NOT_DISTINCT_REQUIRED`, `OPTIMISTIC_LOCK`, `TRIGGER`, `INTERCEPTOR`,
`TARGET_NOT_TRANSFERABLE`). Typical fixes: declare `@KeyUniqueConstraint` on entities whose `@Key` really has a
unique constraint/index; avoid `TRANSACTION_ONLY` triggers; let interceptors declare `ignoreIdOnly`/dependencies.

## Associated save mode

| AssociatedSaveMode | Applies to | Wild targets | Behavior |
|---|---|---|---|
| `REPLACE` (default for `save*`) | backward associations | no | `MERGE` + **dissociate** existing targets missing from the new collection |
| `MERGE` | all | no | insert or update targets; never removes existing links |
| `APPEND` | all | yes | unconditionally insert targets |
| `APPEND_IF_ABSENT` | all | no | insert missing targets, keep existing ones untouched, add missing links |
| `UPDATE` | all | no | update targets by id/key |
| `VIOLENTLY_REPLACE` | backward associations | yes | delete all old links/children, re-insert — slow, can cascade deeply; avoid |

```java
sqlClient.saveCommand(store)
        .setAssociatedModeAll(AssociatedSaveMode.MERGE)                    // every association
        .setAssociatedMode(BookStoreProps.BOOKS, AssociatedSaveMode.APPEND) // one association (wins over *All)
        .execute();
```

Id-only targets are treated as references (link only). Change that with `setIdOnlyAsReference(prop, false)` /
`setIdOnlyAsReferenceAll(false)` (then they follow the associated mode as entities). Jimmer does **not** check by
default that an id-only target exists (`IdOnlyTargetCheckingLevel.NONE`); for fake foreign keys enable
`setAutoIdOnlyTargetChecking(prop)` / `setAutoIdOnlyTargetCheckingAll()` or the global
`setIdOnlyTargetCheckingLevel(IdOnlyTargetCheckingLevel.FAKE | ALL)` → `SaveException.IllegalTargetId`.

Key-only targets as references:

```java
Book book = Immutables.createBook(d -> d
        .setName("Book").setEdition(1).setPrice(BigDecimal.TEN)
        .addIntoAuthors(a -> a.setFirstName("Alex").setLastName("Banks")));   // key of Author
sqlClient.saveCommand(book)
        .setKeyOnlyAsReference(BookProps.AUTHORS)     // look up Author by key; never insert/modify it
        .execute();
```

## How objects are matched: id, key, key groups

- `@Key` props (scalars and/or `@ManyToOne`) form the natural key; `@Key(group = "...")` defines several keys.
  With multiple groups, Jimmer picks the fully loaded group with the **most** properties.
- `@KeyUniqueConstraint` tells Jimmer a unique constraint exists for the key, enabling native upsert. Use
  `noMoreUniqueConstraints = true` if no other unique constraints exist (lets MySQL use `on duplicate key update`
  safely), and `isNullNotDistinct = true` for Postgres 15+ `NULLS NOT DISTINCT` keys with nullable parts.
- Override at runtime: `setKeyProps(BookProps.NAME, BookProps.EDITION)` or `setKeyProps("group", ...)`.
- `matchByKey()` matches root objects by their key **even if an id is supplied** (the supplied id is used only when
  inserting; an existing row keeps its id).

```java
sqlClient.saveCommand(book)
        .setKeyProps(BookProps.NAME, BookProps.EDITION)
        .execute();
```

## Dissociation and child transfer

With `REPLACE`, existing targets that are absent from the new collection are **dissociated**:

- Middle-table associations (`Book.authors`): the middle-table rows are deleted; targets are untouched.
- One-to-many backed by a FK (`BookStore.books`): what happens to the orphaned `Book` rows is decided by the
  `@OnDissociate` action of the **many-to-one** property (`Book.store`) or a per-command override.

| DissociateAction | Effect when a child is dissociated |
|---|---|
| `NONE` (default) | `CHECK` when `setDefaultDissociateActionCheckable(true)` (default), otherwise `LAX` — for real and fake FKs alike |
| `LAX` | do nothing (DB `on delete cascade` may act). Under `REPLACE` it is treated as `CHECK` |
| `CHECK` | throw `SaveException.CannotDissociateTarget` if children would be orphaned |
| `SET_NULL` | set the child FK to NULL (property must be `@Nullable`) |
| `DELETE` | delete the children, recursively dissociating grandchildren. A child type with `@LogicalDeleted` is deleted *logically* only if `setDissociationLogicalDeleteEnabled(true)`, its FK is fake, or the parent itself is being logically deleted; otherwise *physically* |

```java
sqlClient.saveCommand(store)
        .setDissociateAction(BookProps.STORE, DissociateAction.DELETE)   // the many-to-one prop
        .setDissociationLogicalDeleteEnabled(true)    // logically delete orphans whose type has @LogicalDeleted
        .execute();
```

**Child transfer.** Saving `store2` whose `books` contain a book currently owned by `store1` "steals" it. By default
(`TargetTransferMode.AUTO` + global `setTargetTransferable(false)`) this raises `SaveException.TargetIsNotTransferable`.
Allow it per command or globally:

```java
sqlClient.saveCommand(store)
        .setTargetTransferMode(BookStoreProps.BOOKS, TargetTransferMode.ALLOWED)   // or setTargetTransferModeAll(...)
        .execute();
JSqlClient.newBuilder().setTargetTransferable(true);   // global; Spring: jimmer.target-transferable=true
```

## Results, acceptance and re-fetching

```java
SimpleSaveResult<Book> r = sqlClient.save(book);
r.getModifiedEntity();          // the saved entity: generated ids, versions, back references filled in
r.getOriginalEntity();          // what you passed (never mutated)
r.isModified();                 // modifiedEntity != originalEntity (reference comparison only!)
r.isAccepted();                 // did the root take an accepted branch (see below)
r.getTotalAffectedRowCount();
r.getAffectedRowCount(Book.class);
r.getAffectedRowCount(BookProps.AUTHORS);        // middle-table rows
r.getAffectedRowCountMap();                      // Map<AffectedTable, Integer>

BatchSaveResult<Book> br = sqlClient.saveEntities(books);
for (BatchSaveResult.Item<Book> item : br.getItems()) { item.getModifiedEntity(); item.isAccepted(); }
```

`isAccepted()` is `false` for: an existing row skipped by `INSERT_IF_ABSENT`, an update rejected by `setUpdateWhere`,
or an incompatible single-table subtype. An accepted object may have caused zero SQL updates. Don't infer business
outcomes from affected-row counts.

Re-read the saved object in a given shape (uses DML `returning` where the dialect supports it — built in for H2 and
Postgres — and a residual query for the rest, e.g. associations):

```java
Book saved = sqlClient.saveCommand(book)
        .execute(BookFetcher.$.allScalarFields().store(BookStoreFetcher.$.name()))
        .getModifiedEntity();

BookView view = sqlClient.saveCommand(book).execute(BookView.class).getModifiedView();   // output DTO
List<BatchSaveResult.View.ViewItem<Book, BookView>> items =
        sqlClient.saveEntitiesCommand(books).execute(BookView.class).getViewItems();
```

By default, requested values that were written from the saved object are copied, not re-read. If DB triggers or
generated columns can change them, use `.setSaveResultReadsAllProperties()` (or the global
`setDefaultSaveResultReadsAllProperties(true)`). `setSaveReturningEnabled(false)` disables DML returning.

## Controlling the update: mask, condition, assignment, version mode

```java
// 1. Upsert mask: insert everything, but on conflict update only PRICE
sqlClient.saveCommand(book).setUpsertMask(BookProps.PRICE).execute();

UpsertMask<Book> mask = UpsertMask.of(Book.class)
        .addInsertableProp(BookProps.NAME).addInsertableProp(BookProps.EDITION)
        .addInsertableProp(BookProps.PRICE)
        .addUpdatableProp(BookProps.PRICE);                // builder returns new instances — chain it
sqlClient.saveCommand(book).setUpsertMask(mask).execute();

// 2. Accept an existing row but update no property (associations still saved; not INSERT_IF_ABSENT)
sqlClient.saveCommand(book).forbidUpdate().execute();

// 3. Conditional update: rejected rows are NOT errors — check isAccepted()
SimpleSaveResult<Book> r = sqlClient.saveCommand(book)
        .setUpdateWhere(BookTable.class,
                (table, values) -> table.price().lt(values.newNumber(BookProps.PRICE)))   // column on the LEFT
        .execute();

// 4. Assignment expression: compute the new value in SQL (atomic increment)
sqlClient.saveCommand(Immutables.createBook(d -> d.setId(1L).setPrice(new BigDecimal("5"))))
        .setMode(SaveMode.UPDATE_ONLY)
        .set(BookTable.class, BookProps.PRICE,
                (target, values) -> target.price().plus(values.newNumber(BookProps.PRICE)))
        .execute();
```

> **Bug in 0.12.4 — keep the column on the left.** When a comparison has a constant on the left and a column on the
> right, Jimmer swaps the operands with the *negated* operator (`x > col` is rendered `col <= x`). Save commands hit
> this when they evaluate `setUpdateWhere` by a pre-query (e.g. a `DraftInterceptor` needs the original row, triggers
> are `TRANSACTION_ONLY`, or no native upsert): the official form `values.newNumber(P).gt(table.p())` then reports
> equal values as accepted. Write `table.p().lt(values.newNumber(P))` instead — correct on every path. The same applies
> to queries: never write `Expression.value(x).gt(t.price())`.

Rules: `table`/`target` is the **stored** row (local columns only, no joins); `values.newValue/newString/newNumber/
newComparable(prop)` is the incoming value and that property must be loaded in the saved object. `set(...)` only
replaces the assignment of a property that is loaded *and* allowed by the mask; it never adds one. Not usable as
targets: id, discriminator, logical-deleted flag, associations, formulas/transients (and `@Version` unless
`VersionMode.ASSIGNMENT`). Assignments don't apply to inserts. All of these also apply to nested objects of the
same type in a graph save. A command with `setUpdateWhere` rejects mutable forward (many-to-one) target objects —
pass id-only references there.

**VersionMode** (root only):

| Mode | Loaded `@Version` |
|---|---|
| `OPTIMISTIC_LOCK` (default) | adds `VERSION = ?` and `VERSION = VERSION + 1` |
| `ASSIGNMENT` | ordinary value: written as given (subject to mask/assignments), no check, no auto-increment |

```java
sqlClient.saveCommand(store)
        .setVersionMode(VersionMode.ASSIGNMENT)          // e.g. importing externally versioned data
        .setUpdateWhere(BookStoreTable.class,
                (t, v) -> t.version().lt(v.newNumber(BookStoreProps.VERSION)))
        .execute();
```

## Optimistic and pessimistic locking

**Built-in:** a `@Version int version()` property. If the saved object has `version` loaded, the update checks and
increments it; a mismatch throws `SaveException.OptimisticLockError`. If `version` is unloaded, no check is made.
New rows get `@Default` or `0`. A loaded `@Version` (or a custom optimistic lock) makes upserts select first (reason
`OPTIMISTIC_LOCK`), except on dialects that support upsert with an optimistic-lock check (`PostgresDialect`).

**Custom:** an arbitrary predicate (throws `OptimisticLockError` when false). The first argument is the generated
*table class* in Java:

```java
sqlClient.saveEntitiesCommand(stores)
        .setMode(SaveMode.UPDATE_ONLY)
        .setOptimisticLock(BookStoreTable.class, (table, values) ->
                Predicate.sql("coalesce(length(%e), 0) <= length(%e)", new Expression<?>[] {
                        table.website(), values.newString(BookStoreProps.WEBSITE) }))
        .execute();

sqlClient.saveCommand(store)
        .setOptimisticLock(BookStoreTable.class,
                UnloadedVersionBehavior.INCREASE,          // still bump VERSION when the object has no version
                (table, values) -> table.name().eq(values.newString(BookStoreProps.NAME)))
        .execute();
```

`setOptimisticLock` replaced the deprecated `UserOptimisticLock` with `UpdateCondition<E, T>`. Unlike
`setUpdateWhere`, a failed check is an exception. If both are set, `setUpdateWhere` decides eligibility first.

**Pessimistic** (only matters when the command has to *select before writing*): `SELECT ... FOR UPDATE` for the
given types, held until the surrounding transaction ends:

```java
sqlClient.saveEntitiesCommand(books).setPessimisticLock(Book.class).execute();
sqlClient.saveCommand(store).setPessimisticLockAll().execute();
```

UPSERT does not lock an unchanged root row, and saving a graph is not one atomic statement — run it in a transaction
and use explicit locks/isolation when concurrent changes matter.

## Saving input DTOs

Input DTOs (declared with `input` in `.dto` files, see `references/dto-language.md`) implement `Input<E>` and convert to entities:

```java
sqlClient.save(bookInput);                                       // = saveCommand(input.toEntity())
sqlClient.saveInputs(List.of(in1, in2));
sqlClient.saveCommand(bookInput).setMode(SaveMode.UPDATE_ONLY).execute();
Book entity = bookInput.toEntity();                              // when you need to adjust before saving
```

- `id(store)` / `id(authors) as authorIds` in the DTO become id-only (reference) associations.
- Null handling: a `null` field in a *static* input sets the column to NULL; `dynamic` inputs (or `null`-modes
  `dynamic`/`fuzzy`) leave the property unloaded when the field is null/absent, enabling partial updates from PATCH-like
  requests.
- Recursive input trees keyed by `(name, parent)`: set the root's parent explicitly before saving, e.g.
  `Immutables.createTreeNode(input.toEntity(), d -> d.setParent(null))`.

## Errors and exception translation

`SaveException` (in `org.babyfish.jimmer.sql.exception`) carries `getPath()`/`getExportedPath()` (where in the graph)
and `getSaveErrorCode()`. Common subclasses:

| Exception | Cause |
|---|---|
| `NeitherIdNorKey` | object has neither id nor complete key (use key props, `setKeyProps`, or `INSERT_ONLY`) |
| `NotUnique` | unique-constraint violation, translated with the conflicting props/values |
| `OptimisticLockError` | version/custom optimistic check failed |
| `CannotDissociateTarget` | `CHECK` dissociation found children |
| `TargetIsNotTransferable` | child already belongs to another parent |
| `IllegalTargetId` | id-only target doesn't exist (when target checking is enabled) |
| `NullTarget` | non-null reference set to null |
| `NoIdGenerator`, `IllegalIdGenerator`, `IllegalGeneratedId` | id generation misconfiguration |
| `NoKeyProp`, `NoVersion`, `IncompleteProperty` | missing required metadata/values |
| `ReadonlyMiddleTable`, `UnstructuredAssociation`, `ReversedRemoteAssociation`, `LongRemoteAssociation`, `FailedRemoteValidation` | association cannot be saved this way |
| `IllegalInterceptorBehavior` | a `DraftInterceptor` changed id/key (not detected in every case — never do it) |

Constraint-violation investigation is on by default (`setConstraintViolationTranslatable(true)`): after a DB error,
Jimmer queries to find the conflicting key and throws `SaveException.NotUnique`. Translate it to a business exception
per command or globally (`JSqlClient.Builder.addExceptionTranslator`, or a Spring `ExceptionTranslator` bean):

```java
sqlClient.saveCommand(book)
        .addExceptionTranslator(new ExceptionTranslator<SaveException.NotUnique>() {
            @Override
            public @Nullable Exception translate(@NotNull SaveException.NotUnique ex, @Nullable Args args) {
                if (ex.isMatched(BookProps.NAME, BookProps.EDITION)) {
                    return new IllegalArgumentException("Book " + ex.getValue(BookProps.NAME) +
                            " edition " + ex.getValue(BookProps.EDITION) + " already exists");
                }
                return null;       // not handled: keep the original
            }
        })
        .execute();
```

Write translators as anonymous classes or named classes: a **lambda** fails at runtime with
`IllegalArgumentException: Illegal exception translator type ... is not class type`, because Jimmer reads the
generic argument reflectively.

A returned checked exception is wrapped in `ExecutionException`; return a `RuntimeException` to avoid that. This
replaces "check-then-insert" pre-queries, which are racy.

## Inheritance

Saving a subtype object (`Organization`, `Person`) of an inheritance hierarchy writes its root table (and the
`JOINED` subtype table). Changing an existing row's concrete type requires `setTypeChangeAllowed()` (root) or
`setAssociatedTypeChangeAllowed(...)` (targets). `setTypeMatchMode(TypeMatchMode.EXACT)` /
`setAssociatedTypeMatchMode(...)` control whether a single-table update only matches rows of the exact subtype
(rejected rows have `isAccepted() == false`).

## Batching, MySQL and transactions

- Saves are batched per entity type and per shape: `saveEntities` groups objects with the same loaded shape.
- **MySQL** batches are off by default. To enable: JDBC URL `rewriteBatchedStatements=true`, then
  `setExplicitBatchEnabled(true)` (`jimmer.explicit-batch-enabled`). Such "dumb" batches can't return generated ids or
  per-row counts, so Jimmer only uses them when acceptable: call `setDumbBatchAcceptable()` on the command (preferred)
  or globally.
- `setMaxCommandJoinCount(n)` (default 2) bounds joins used in generated mutation SQL.
- Without Spring, a save is not automatically transactional: wrap it in `sqlClient.transaction(() -> ...)` (needs a
  transaction-aware `ConnectionManager`) or your framework's transaction. `setTransactionRequired(true)` /
  `setMutationTransactionRequired(true)` make Jimmer fail fast when no transaction is active.

## Global defaults (builder)

| Builder method | Default | Spring property |
|---|---|---|
| `setTargetTransferable(boolean)` | `false` | `jimmer.target-transferable` |
| `setDefaultDissociateActionCheckable(boolean)` | `true` | `jimmer.default-dissociation-action-checkable` |
| `setIdOnlyTargetCheckingLevel(level)` | `NONE` | `jimmer.id-only-target-checking-level` |
| `setDissociationLogicalDeleteEnabled(boolean)` | `false` | `jimmer.dissociation-logical-delete-enabled` |
| `setDefaultSaveReturningEnabled(boolean)` | `true` | `jimmer.default-save-returning-enabled` |
| `setDefaultSaveResultReadsAllProperties(boolean)` | `false` | `jimmer.default-save-result-reads-all-properties` |
| `setDefaultTypeChangeAllowed(boolean)` | `false` | `jimmer.default-type-change-allowed` |
| `setConstraintViolationTranslatable(boolean)` | `true` | `jimmer.constraint-violation-translatable` |
| `setExplicitBatchEnabled(boolean)` / `setDumbBatchAcceptable(boolean)` | `false` | `jimmer.explicit-batch-enabled` / `jimmer.dumb-batch-acceptable` |
| `setMaxCommandJoinCount(int)` | `2` | `jimmer.max-command-join-count` |
| `setMutationTransactionRequired(boolean)` | `false` | `jimmer.mutation-transaction-required` |
| `addExceptionTranslator(...)` | — | `ExceptionTranslator` beans |

## Command option cheat sheet

| Option | Purpose |
|---|---|
| `setMode(SaveMode)` | root insert/update behavior |
| `setAssociatedModeAll(mode)` / `setAssociatedMode(prop, mode)` | association behavior |
| `setKeyProps([group,] props...)` / `matchByKey()` | how roots are matched |
| `setUpsertMask(props... or UpsertMask)` / `forbidUpdate()` | which columns an upsert may insert/update |
| `setUpdateWhere(TableClass, cond)` | conditional update (non-exception rejection) |
| `set(TableClass, prop, expr)` | SQL-computed assignment |
| `setVersionMode(VersionMode)` | `@Version` as lock or plain value |
| `setOptimisticLock(TableClass, [UnloadedVersionBehavior,] cond)` | custom optimistic lock (exception) |
| `setPessimisticLock(Class[, bool])` / `setPessimisticLockAll()` | `FOR UPDATE` on pre-queries |
| `setDissociateAction(manyToOneProp, action)` / `setDissociationLogicalDeleteEnabled(bool)` | orphan handling |
| `setTargetTransferMode(oneToManyProp, mode)` / `setTargetTransferModeAll(mode)` | allow re-parenting children |
| `setIdOnlyAsReference[All](...)` / `setKeyOnlyAsReference[All](...)` | treat id-only / key-only targets as references |
| `setAutoIdOnlyTargetChecking[All](...)` | verify id-only targets exist |
| `setDeleteMode(DeleteMode)` | how dissociated children are deleted (`AUTO`/`LOGICAL`/`PHYSICAL`) |
| `setTypeMatchMode`, `setAssociatedTypeMatchMode*`, `setTypeChangeAllowed`, `setAssociatedTypeChangeAllowed*` | inheritance |
| `setSaveReturningEnabled(bool)`, `setSaveResultReadsAllProperties()` | how results are materialized |
| `setConstraintViolationTranslatable(bool)`, `addExceptionTranslator(t)` | error translation |
| `setDumbBatchAcceptable()`, `setMaxCommandJoinCount(n)`, `setTransactionRequired(bool)` | execution |
| `execute()`, `execute(Fetcher)`, `execute(ViewClass)`, `execute(Connection[, ...])` | run |
