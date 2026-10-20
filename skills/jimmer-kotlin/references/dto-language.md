# DTO language (Kotlin)

## Contents
- [Why and where](#why-and-where)
- [Files, export, package, imports](#files-export-package-imports)
- [Three DTO kinds](#three-dto-kinds)
- [Properties and macros](#properties-and-macros)
- [Nullability modifiers](#nullability-modifiers)
- [Renaming](#renaming)
- [Associations: nested, id(), recursive, flat, fold](#associations-nested-id-recursive-flat-fold)
- [Enum mapping and user-defined properties](#enum-mapping-and-user-defined-properties)
- [Annotations, doc comments, implements](#annotations-doc-comments-implements)
- [Input DTOs: null handling modes](#input-dtos-null-handling-modes)
- [Specifications (super QBE)](#specifications-super-qbe)
- [Output DTO association options](#output-dto-association-options)
- [Composition: for, fragments and reuse](#composition-for-fragments-and-reuse)
- [Polymorphic DTOs (#types)](#polymorphic-dtos-types)
- [Generated Kotlin code and usage](#generated-kotlin-code-and-usage)

## Why and where

Entities already express any shape, so output DTOs are optional; **input DTOs are recommended for every public
write API** (explicit, secure contract). `.dto` files live in `src/main/dto/**` and are compiled by `jimmer-ksp`
(no `@EnableDtoGeneration` needed in Kotlin). After editing only `.dto` files make sure KSP re-runs (see the
`KspAATask` inputs recipe in `references/setup-and-config.md`).

## Files, export, package, imports

```
export com.example.model.Book
    -> package com.example.dto                 // default: <entity package>.dto

import java.time.LocalDateTime
import com.example.runtime.{AuthorFieldFilter, ChildNodesRecursionStrategy}   // grouped imports, aliases with `as`

BookView { ... }
input BookInput { ... }
specification BookSpecification { ... }
```

Without `export`, the file path mirrors the entity (`src/main/dto/com/example/model/Book.dto`). `//`, `/* */`
comments; `/** */` doc comments are copied to generated code.

## Three DTO kinds

| Declaration | Generated class implements | Use |
|---|---|---|
| `BookView { }` | `View<Book>` | query output |
| `input BookInput { }` | `Input<Book>` | save input |
| `specification BookSpecification { }` | `KSpecification<Book>` | `where(spec)` |

Modifiers: `input`, `specification`, `unsafe` (input), `fixed|static|dynamic|fuzzy` (input null mode), `sealed` (`#types`).

## Properties and macros

```
BookView {
    #allScalars            // all scalar props incl. inherited (not formulas/transients/id views/logical-deleted/@ExcludeFromAllScalars)
    #allScalars(this)      // only props declared by Book itself
    #allScalars(Book, TenantAware)
    -tenant                // remove a prop added by a macro
    #allReferences         // every FK reference as id(...)
    name
}
```

## Nullability modifiers

- Default nullability follows the entity; specification props are nullable; an input's id is nullable when the id
  has a generator.
- `prop?` / `#allScalars?`: make non-null props nullable; in inputs a `null` then means "leave unloaded".
- `prop!`: make nullable props non-null — only for the input id (`id!`), any prop of an `unsafe input`, or specification props.

```
input UpdateBookInput {
    #allScalars?
    id!
    -tenant
}
```

## Renaming

```
BookView {
    name as title
    authors as authorList { id }
    as(^ -> book) { edition price }            // bookEdition, bookPrice
}
```
Patterns: `^ -> p` (add prefix), `^p ->`, `^p -> q`, `$ -> s` (add suffix), `s $ ->` (remove suffix), `s $ -> t`
(replace suffix — the space before `$` is required), `x ->`, `x -> y`. Example: `as(Time $ -> At) { createdTime }` →
`createdAt`.

## Associations: nested, id(), recursive, flat, fold

```
input CompositeBookStoreInput {
    #allScalars(this)
    -id
    books {                                    // nested class CompositeBookStoreInput.TargetOf_books
        #allScalars(this)
        -id
        id(authors) as authorIds
    }
}

BookView {
    id(store)                                  // storeId
    id(authors) as authorIds                   // List<Long>
}

TreeNodeView { #allScalars  childNodes* }      // recursive

FlatTreeNodeView {
    #allScalars(this)
    flat(parent) { as(^ -> parent) { #allScalars(this) } }   // parentId, parentName
}

FlatBookStoreView {
    id
    name
    fold(meta) { website  version }            // nested object TargetOf_meta
}
```

Use `id(...)` for short associations (link existing rows) and nested bodies for long associations (save children too).

## Enum mapping and user-defined properties

```
AuthorGenderView for com.example.model.Author {
    id
    gender -> { MALE: 100  FEMALE: 101 }       // DTO property becomes Int
}

BookWithCustom {
    id
    remark: String                             // not mapped; Kotlin type in the generated class
    rank: Int = 0
    tags: List<String>
}
```

## Annotations, doc comments, implements

```
/** Book with validation */
input BookInput implements com.example.HasName {
    #allScalars
    @Size(min = 2, max = 50) name
    @Size(max = 200) remark: String
}
```
Entity annotations are copied unless the DTO prop declares an annotation of the same type; Jimmer's own annotations
(except `org.babyfish.jimmer.client.*`) are never copied. Arrays: `[...]` or `{...}`.

## Input DTOs: null handling modes

For **nullable** input props:

| Mode | JSON `"storeId": null` | JSON without `storeId` |
|---|---|---|
| `static` (default) | set `null` | set `null` |
| `fixed` | set `null` | **400 error** |
| `dynamic` | set `null` | leave unloaded |
| `fuzzy` | leave unloaded | leave unloaded |

```
dynamic input BookPatch {
    id!
    name?                      // entity prop is non-null: a null here can only mean "unloaded"
    price?
    id(store)                  // entity prop is nullable: null may mean "unloaded" or "set NULL"
}

input BookFixedPatch {
    id!
    fixed id(store)            // JSON must contain storeId (may be null)
}
```

In Kotlin every `dynamic` prop `xxx` gets a companion flag `isXxxLoaded` (constructor parameter, default
`xxx !== null`). JSON deserialization sets it from presence; in code pass it explicitly to write an explicit `null`:

```kotlin
BookPatch(id = 1L, price = BigDecimal.TEN)                        // name and store untouched
BookPatch(id = 1L, storeId = null, isStoreIdLoaded = true)       // STORE_ID := NULL
// an explicit null for `name` still leaves it unloaded, because Book.name is non-null
```

Priority: prop modifier > type modifier > KSP option `jimmer.dto.defaultNullableInputModifier` > `static`.

## Specifications (super QBE)

```
specification BookSpecification {
    like/i(name)                               // options i, ^, $ (like/i^$)
    ge(price)                                  // minPrice
    le(price)                                  // maxPrice
    valueIn(edition) as editions               // Collection<Int>?
    null(store) as noStore                     // Boolean (default false): store is null when true
    associatedIdIn(store) as storeIds
    flat(store) { like/i(name) as storeName }
    flat(authors) { like/i(firstName, lastName) as authorName }   // collection → EXISTS; multi-prop = OR
}
```

```kotlin
val spec = BookSpecification(name = "graphql", minPrice = BigDecimal(40), authorName = "eve")
sqlClient.createQuery(Book::class) {
    where(spec)                                 // null properties add no predicate
    select(table)
}
```

QBE functions: `eq ne gt ge lt le like notLike null notNull valueIn valueNotIn associatedIdEq associatedIdNe
associatedIdIn associatedIdNotIn`.

## Output DTO association options

```
BookDetailView {
    #allScalars(this)
    !fetchType(JOIN_ALWAYS)
    store { #allScalars  -version }
    !batch(32)
    !orderBy(lastName asc, firstName asc)
    !where(firstName ilike '%a%')
    !limit(20)
    authors { id firstName lastName }
}

BookFilterClassView {
    id
    !filter(AuthorFieldFilter)                 // Kotlin: class implementing KFieldFilter<Author>
    authors { id firstName }
}

RecursiveTreeView for com.example.model.TreeNode {
    id
    name
    !recursion(ChildNodesRecursionStrategy)    // RecursionStrategy<TreeNode>; or !depth(2)
    childNodes*
}
```

```kotlin
class AuthorFieldFilter : KFieldFilter<Author> {
    override fun KFieldFilterDsl<Author>.applyTo() {
        where(table.firstName ilike "a")
        orderBy(table.lastName)
    }
}

class ChildNodesRecursionStrategy : RecursionStrategy<TreeNode> {
    override fun isRecursive(args: RecursionStrategy.Args<TreeNode>): Boolean = args.entity.name != "Clothing"
}
```

## Composition: for, fragments and reuse

```
package com.example.dto.catalog

fragment AuthorNameFields for com.example.model.Author { firstName  lastName }

AuthorItem for com.example.model.Author { id  #include(AuthorNameFields) }
StoreItem for com.example.model.BookStore { id  name }

BookCard for com.example.model.Book {
    id
    name
    store -> StoreItem                        // reuse an existing DTO type
    authors -> AuthorItem                     // List<AuthorItem>
}
```

## Polymorphic DTOs (#types)

```
export com.example.mapping.Client -> package com.example.dto.client

ClientView {
    id
    name
    #types {
        Organization { taxCode }
        Person { firstName lastName }
    }
}
```

Kotlin: `ClientView` is an interface with nested classes `ClientView.Organization`, `ClientView.Person` and
`ClientView.Default`.

## Generated Kotlin code and usage

```kotlin
// immutable classes with named constructor parameters (defaults: null for nullable props) and copy(...)
val input = BookInput(name = "DTO Book", edition = 1, price = BigDecimal("12.5"), storeId = 2L, authorIds = listOf(1L, 2L))
sqlClient.save(input)                                        // = save(input.toEntity())
val entity: Book = input.toEntity { tenant = "a" }          // adjust the entity while converting
val nested = CompositeBookStoreInput(
    name = "COMPOSITE",
    books = listOf(CompositeBookStoreInput.TargetOf_books(name = "B", edition = 1, price = BigDecimal.ONE, authorIds = listOf(3L)))
)

val view = BookView(entity)                                 // entity → DTO
BookView.METADATA.fetcher                                   // fetcher for queries
sqlClient.findById(BookView::class, 1L)
```

KSP option `jimmer.dto.mutable=true` turns DTO properties into `var`s (needed by some binders).
