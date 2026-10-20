# Immutable objects and drafts (Java)

## Contents
- [Dynamic, immutable entities](#dynamic-immutable-entities)
- [Creating objects](#creating-objects)
- [Copy-and-modify](#copy-and-modify)
- [Draft API per property kind](#draft-api-per-property-kind)
- [ImmutableObjects utilities](#immutableobjects-utilities)
- [DraftObjects utilities (dynamic access inside drafts)](#draftobjects-utilities-dynamic-access-inside-drafts)
- [Visibility](#visibility)
- [JSON](#json)
- [Equality](#equality)
- [Non-ORM immutable types](#non-orm-immutable-types)

## Dynamic, immutable entities

- Each property is either **loaded** or **unloaded**. Unloaded is not `null`.
- Reading an unloaded property throws `org.babyfish.jimmer.UnloadedException` (similar to Hibernate's
  `LazyInitializationException`, but nothing is ever lazily loaded).
- Jackson serialization (and `toString()`) skips unloaded properties.
- Objects are deeply immutable and safe to share across threads; circular references are impossible.
- Query results are shaped by fetchers; saved objects may be partial ("incomplete objects"). This is how Jimmer
  distinguishes "don't touch this column" (unloaded) from "set it to NULL" (loaded `null`).

## Creating objects

```java
// style 1: summary class (generated in the common package of the module's entities)
Book book = Immutables.createBook(draft -> {
    draft.setName("SQL in Action");
    draft.setEdition(1);
    draft.setPrice(new BigDecimal("49.9"));
});

// style 2: the draft's producer
Book book2 = BookDraft.$.produce(draft -> draft.setName("Kotlin in Action").setEdition(1));

// setters return the draft: chaining works
Book idOnly = Immutables.createBook(d -> d.setId(10L));
Book same = ImmutableObjects.makeIdOnly(Book.class, 10L);
```

A draft is only valid inside its lambda — never let it escape.

## Copy-and-modify

```java
Book cheaper = Immutables.createBook(book, draft -> draft.setPrice(new BigDecimal("39.9")));
Book renamed = BookDraft.$.produce(book, draft -> draft.setName("New name"));
// `book` itself is unchanged; untouched sub-objects are shared, not copied
```

Deep edits are natural:
```java
TreeNode updated = Immutables.createTreeNode(tree, draft -> {
    draft.childNodes(false).get(0).setName("Son");                       // List<TreeNodeDraft>
    draft.childNodes(false).get(0).childNodes(false).get(0).setName("Grandson");
    draft.parent(true).setName("Daddy");                                 // auto-creates the parent if unset/null
});
```

## Draft API per property kind

For an entity `Book` with `name`, `store` (reference), `authors` (list), `@IdView storeId`, `@IdView("authors") authorIds`,
`BookDraft` generates:

| Kind | Generated members |
|---|---|
| Scalar | `BookDraft setName(String)` |
| Reference | `BookStoreDraft store()` (draft of the current value), `BookStoreDraft store(boolean autoCreate)`, `setStore(BookStore)`, `applyStore(DraftConsumer<BookStoreDraft>)`, `applyStore(BookStore base, DraftConsumer<...>)` |
| List | `List<AuthorDraft> authors(boolean autoCreate)`, `setAuthors(List<Author>)`, `addIntoAuthors(DraftConsumer<AuthorDraft>)`, `addIntoAuthors(Author base, DraftConsumer<...>)` |
| `@IdView` | `setStoreId(Long)` (replaces `store` with an id-only object), `setAuthorIds(List<Long>)`, `authorIds(boolean)` |

```java
Book book = Immutables.createBook(d -> {
    d.setName("GraphQL in Action").setEdition(3).setPrice(new BigDecimal("80"));
    d.applyStore(store -> store.setId(2L));          // reference to an existing store (id-only)
    d.addIntoAuthors(a -> a.setId(5L));              // short association: id-only objects
    d.addIntoAuthors(a -> {                          // or a new author (save will insert/match it by key)
        a.setFirstName("Samer").setLastName("Buna").setGender(Gender.MALE);
    });
});

Book book2 = Immutables.createBook(d -> d.setName("X").setStoreId(2L).setAuthorIds(List.of(1L, 2L))); // with @IdView
Book noStore = Immutables.createBook(d -> d.setId(8L).setStore(null)); // explicitly null (save sets FK to NULL)
```

Rules: `addIntoXxx` auto-creates the list; `xxx(false)` throws if unloaded; `store(true)` creates an empty
child draft when the reference is unloaded or null. Prefer `xxx(false)` over casting `List<Book>` elements to drafts.

## ImmutableObjects utilities

`org.babyfish.jimmer.ImmutableObjects` — typed overloads accept `BookProps.XXX` (`TypedProp`), `ImmutableProp`, `PropId` or `String`.

| Method | Purpose |
|---|---|
| `isLoaded(obj, BookProps.STORE)` | is the property loaded |
| `get(obj, BookProps.STORE)` | dynamic get (throws if unloaded) |
| `isLoadedChain(obj, propIds...)` | deep loaded check |
| `makeIdOnly(Book.class, id)` | id-only object (`null` id → `null`) |
| `isIdOnly(obj)` | only the id is loaded |
| `toIdOnly(obj)` / `toIdOnly(list)` | strip to ids |
| `isLonely(obj)` / `toLonely(obj)` | no associations except FK references as id-only objects |
| `merge(a, b, ...)` | merge loaded props of several objects of the same type (later wins) |
| `deepClone(obj)` | deep copy |
| `isLogicalDeleted(obj)` | evaluates the `@LogicalDeleted` flag |
| `getDiscriminator(obj)` | discriminator value for inheritance types |
| `emptyStringToNull(objOrCollection)` | converts `""` to `null` for nullable string props (handy for form input) |
| `toString(obj)` / `fromString(Book.class, json)` | JSON round trip using the default `JsonCodec` |

## DraftObjects utilities (dynamic access inside drafts)

```java
Book b2 = Immutables.createBook(book, draft -> {
    DraftObjects.set(draft, BookProps.PRICE, BigDecimal.TEN);
    DraftObjects.unload(draft, BookProps.AUTHORS);   // make it unloaded again (save will not touch it)
    DraftObjects.hide(draft, BookProps.STORE);       // loaded but hidden from JSON
    DraftObjects.show(draft, BookProps.STORE_ID);
});
```

## Visibility

Visibility only affects JSON output. It is how view properties work: fetching `storeId()` loads `store`
internally but shows `storeId` and hides `store`; fetching `fullName()` (a `@Formula` with dependencies) loads
`firstName`/`lastName` but only `fullName` is serialized. A property is serialized only when it is loaded **and** visible.

## JSON

- `book.toString()` prints JSON (only loaded, visible props).
- Use Jimmer's Jackson module (`ImmutableModuleV2` for Jackson 2, `ImmutableModuleV3` for Jackson 3); the Spring Boot
  starter registers it. Deserializing JSON into an entity yields an object whose loaded props are exactly the JSON fields
  — so a REST endpoint can accept a partial entity, but prefer **input DTOs** for public APIs (explicit contract, security).
- `@JsonConverter` customizes serialization of a property (see `references/entity-mapping.md`).

## Equality

`equals`/`hashCode` are value based on the object's shape: if both objects have the id loaded, only ids are compared;
otherwise loaded state, visibility and values of every property must match. Without loaded ids, objects of different shapes are not equal.

## Non-ORM immutable types

```java
@Immutable
public interface TreeNode {
    String name();
    @Nullable TreeNode parent();
    List<TreeNode> childNodes();
}
```
`@Immutable` types get drafts, `Immutables.createTreeNode`, JSON support and `ImmutableObjects` utilities, but no SQL DSL.
