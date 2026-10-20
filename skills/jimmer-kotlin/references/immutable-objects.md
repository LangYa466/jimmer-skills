# Immutable objects and drafts (Kotlin)

## Contents
- [Dynamic, immutable entities](#dynamic-immutable-entities)
- [Creating objects](#creating-objects)
- [Copy-and-modify](#copy-and-modify)
- [Draft API per property kind](#draft-api-per-property-kind)
- [Utilities (org.babyfish.jimmer.kt)](#utilities-orgbabyfishjimmerkt)
- [Visibility, JSON, equality](#visibility-json-equality)
- [Non-ORM immutable types](#non-orm-immutable-types)

## Dynamic, immutable entities

- Every property is **loaded** or **unloaded**; unloaded ≠ `null`. Reading an unloaded property throws
  `UnloadedException` — even for a Kotlin non-null type. Nothing is lazily loaded.
- JSON/`toString()` skip unloaded properties.
- For saves, unloaded = "don't touch the column", loaded `null` = "set NULL".
- Objects are deeply immutable and thread-safe.

## Creating objects

KSP generates a factory function named after the entity, plus `copy`:

```kotlin
val book = Book {                         // generated in the entity's package (BookDraft.kt)
    name = "SQL in Action"
    edition = 1
    price = BigDecimal("49.9")
    store { id = 1L }                     // reference: edit the (auto-created) child draft
    authors().addBy { id = 1L }           // list: add a child draft
    authors().addBy { id = 2L }
}

val idOnly = makeIdOnly(Book::class, 10L)     // org.babyfish.jimmer.kt.makeIdOnly (also makeIdOnly<Book>(10L))
val viaNew = new(Book::class).by { name = "X" } // org.babyfish.jimmer.kt.new — same as Book { }
```

The draft (`this` inside the lambda) must not escape the lambda.
Local variables and parameters shadow draft properties: with a parameter `name` in scope, `Book { name = name }`
does not compile (`'val' cannot be reassigned`) — write `this.name = name`.

## Copy-and-modify

```kotlin
val cheaper = book.copy { price = BigDecimal("39.9") }      // `book` itself is unchanged
val book3 = Book(book) { edition = 2 }                      // same, constructor-like form

val tree2 = tree.copy {
    childNodes()[0].name = "Child-1*"                       // deep edit through child drafts
    parent { name = "Daddy" }                               // creates the parent draft if unloaded/null
}
```

## Draft API per property kind

For `Book` with `name`, `store` (reference), `authors` (list), `@IdView storeId`, `@IdView("authors") authorIds`:

| Kind | Generated in `BookDraft` |
|---|---|
| Scalar | `var name: String` |
| Reference | `var store: BookStore?`, `fun store(): BookStoreDraft` (auto-creates), `fun store(block: BookStoreDraft.() -> Unit)` |
| List | `var authors: List<Author>`, `fun authors(): MutableList<AuthorDraft>`, extension `MutableList<AuthorDraft>.addBy { }` / `addBy(base)` |
| `@IdView` | `var storeId: Long?`, `var authorIds: List<Long>`, `fun authorIds(): MutableList<Long>` |

```kotlin
val b = Book {
    name = "GraphQL in Action"; edition = 3; price = BigDecimal("80")
    storeId = 2L                         // with @IdView; else: store = makeIdOnly(BookStore::class, 2L)
    authorIds = listOf(1L, 2L)
}
val noStore = Book { id = 8L; store = null }    // explicit null → save sets the FK to NULL
```

## Utilities (org.babyfish.jimmer.kt)

| Function | Purpose |
|---|---|
| `isLoaded(obj, Book::store)` | is the property loaded |
| `get(obj, Book::store)` | dynamic get |
| `makeIdOnly(Book::class, id)` / `makeIdOnly<Book>(id)` / `makeNullableIdOnly(...)` | id-only object |
| `toIdOnly(obj)`, `isLonely(obj)`, `toLonely(obj)` | strip to id / to lonely object |
| `merge(a, b, ...)` | merge loaded props (later wins) |
| `deepClone(obj)` | deep copy |
| `getDiscriminator(obj)` | inheritance discriminator |
| `fromString<Book>(json)` | parse JSON with the default `JsonCodec` |
| `set(draft, BookDraft::price, v)`, `unload(draft, BookDraft::authors)`, `show(...)`, `hide(...)` | dynamic draft access (draft props) |
| `Book::name.toImmutableProp()` | metadata |

`org.babyfish.jimmer.ImmutableObjects` (Java API) also works: `ImmutableObjects.isIdOnly(obj)`.

## Visibility, JSON, equality

- Visibility only affects JSON: fetching `storeId` loads `store` but hides it; a `@Formula` with dependencies shows
  only itself.
- Register `ImmutableModuleV2` (Jackson 2) / `ImmutableModuleV3` (Jackson 3); the starter does it. Prefer input DTOs
  to accepting partial entities in public APIs.
- `equals`: if both objects have ids loaded, ids are compared; otherwise the loaded shape (including internal
  state such as visibility) and values. An object read back from JSON is not necessarily `==` to the original even
  with identical fields — compare ids or fields instead.

## Non-ORM immutable types

```kotlin
@Immutable
interface TreeNode {
    val name: String
    val parent: TreeNode?
    val childNodes: List<TreeNode>
}
```
`@Immutable` types get drafts, `TreeNode { }`, `copy { }` and JSON support, but no SQL DSL. (One Jimmer type per file.)
