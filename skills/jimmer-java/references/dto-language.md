# DTO language (Java)

## Contents
- [Why and where](#why-and-where)
- [Files, export, package, imports](#files-export-package-imports)
- [Three DTO kinds](#three-dto-kinds)
- [Properties and macros](#properties-and-macros)
- [Nullability modifiers](#nullability-modifiers)
- [Renaming](#renaming)
- [Associations: nested, id(), recursive, flat, fold](#associations-nested-id-recursive-flat-fold)
- [Enum mapping](#enum-mapping)
- [User-defined properties](#user-defined-properties)
- [Annotations, doc comments, implements](#annotations-doc-comments-implements)
- [Input DTOs: null handling modes](#input-dtos-null-handling-modes)
- [Specifications (super QBE)](#specifications-super-qbe)
- [Output DTO association options](#output-dto-association-options)
- [Composition: for, fragments and reuse](#composition-for-fragments-and-reuse)
- [Polymorphic DTOs (#types)](#polymorphic-dtos-types)
- [Generated Java code and usage](#generated-java-code-and-usage)
- [Build notes](#build-notes)

## Why and where

Jimmer entities already express arbitrary shapes, so output DTOs are optional. **Input DTOs are not**: public write
APIs need explicit, static, secure contracts. The DTO language generates static DTO classes, conversion to/from
entities and matching fetchers at compile time — much cheaper than hand-written DTOs or MapStruct.

Files live in `src/main/dto/**/*.dto` (not resources). They are compiled by `jimmer-apt` in any module that can see
the entities. Java modules without entities need `@EnableDtoGeneration` on some class.

## Files, export, package, imports

```
export com.example.model.Book                 // entity of declarations without `for`
    -> package com.example.dto                // package of generated classes (default: <entity package>.dto)

import java.time.LocalDateTime
import java.time.{ LocalDate, OffsetDateTime as ODT }   // grouped / aliased imports; `.*` also allowed
import com.fasterxml.jackson.annotation.JsonProperty

BookView { ... }
input BookInput { ... }
specification BookSpecification { ... }
```

Without `export`, the file path must mirror the entity: `src/main/dto/com/example/model/Book.dto`.
A file may also start with `package com.example.dto` and give every declaration an explicit target with `for`
(see composition). Comments: `//` and `/* */`; `/** ... */` doc comments are copied into generated code.

## Three DTO kinds

| Declaration | Generated type implements | Use |
|---|---|---|
| `BookView { }` | `View<Book>` | query output (optional — prefer fetchers internally) |
| `input BookInput { }` | `Input<Book>` | save command input (required for public write APIs) |
| `specification BookSpecification { }` | `JSpecification<Book, BookTable>` | query parameter (`where(spec)`) |

Modifiers before the name: `input`, `specification`, `unsafe` (input only), null-mode `fixed|static|dynamic|fuzzy`
(input only), `sealed` (only with `#types`).

## Properties and macros

```
BookView {
    #allScalars            // every scalar prop incl. inherited ones (not formulas, transients, id views,
                           //   logical-deleted flag, @ExcludeFromAllScalars)
    #allScalars(this)      // only props declared by Book itself
    #allScalars(Book, TenantAware)   // props declared by the listed types (this/entity or super types)
    -tenant                // negative: remove a prop (by DTO alias) added by a macro
    #allReferences         // every FK reference as id(...) → storeId
    name                   // explicit prop
}
```

Explicit props, macros and negatives can appear in any order. Input DTOs can only contain savable props (no
formulas, transients, remote associations).

## Nullability modifiers

- Default: same nullability as the entity, except every specification prop is nullable, and an input DTO's id is
  nullable when the entity id has a generator.
- `prop?` / `#allScalars?`: make non-null props nullable (compile error if already nullable; not allowed in specifications).
  For input DTOs a `null` value of an originally non-null prop leaves the entity prop **unloaded** (not updated).
- `prop!`: make nullable props non-null. Only allowed for: the id of an `input` (`id!`), any prop of an
  `unsafe input` (`store! { id }`), or props of a `specification`.

```
input BookUpdateInput {
    #allScalars?           // partial update: only provided fields are written
    id!                    // id is required
    -tenant
}
```

## Renaming

```
BookView {
    name as title
    authors as authorList { id }               // `as` goes before the child body
    as(^ -> book) { edition price }            // prefix all: bookEdition, bookPrice
    as($ -> Text) { name }                     // suffix: nameText
}
```
`as(...)` patterns: `^ -> p` add prefix, `^p ->` remove prefix, `^p -> q` replace prefix, `$ -> s` add suffix,
`$s ->` remove suffix, `$s -> t` replace suffix, `x ->` remove infix, `x -> y` replace infix (`^` and `$` cannot be combined).

## Associations: nested, id(), recursive, flat, fold

```
input CompositeBookInput {
    #allScalars
    store { #allScalars  -id }                 // nested object → generated nested class TargetOf_store
    authors { #allScalars  -id }               // List<TargetOf_authors>
}

BookView {
    id(store)                                  // associated id → storeId (no @IdView needed)
    id(authors) as authorIds                   // List<Long>
}

TreeNodeView {
    #allScalars
    childNodes*                                // recursive: same shape at every level
}

FlatTreeNodeView {                             // flat: promote reference props into the current DTO
    #allScalars(this)                          // id, name
    flat(parent) {
        as(^ -> parent) { #allScalars(this) }  // parentId, parentName
        flat(parent) {
            as(^ -> grandParent) { #allScalars(this) }
        }
    }
}

BookStoreView {                                // fold: group current-level props into a nested object
    id
    fold(meta) {                               // nested class TargetOf_meta; fold(meta)? makes it nullable
        website
        version
    }
}
```

- `flat` works only on references for view/input, but on collections too for specifications.
- Prefer `id(store)` / `id(authors) as authorIds` for "short associations" in inputs (link to existing rows);
  nested bodies for "long associations" (save child objects too).
- Do not rely on entity `@IdView` props inside DTOs; use `id(...)`.

## Enum mapping

```
AuthorView {
    #allScalars
    gender -> { MALE: 100  FEMALE: 101 }          // DTO prop becomes int
    // gender -> { MALE: "Male"  FEMALE: "Female" } // or String
}
```

## User-defined properties

```
input BookInput {
    #allScalars
    remark: String                   // not mapped to the entity; must declare a type
    rank: Int = 0                    // default value
    tags: List<String>
    extra: Map<String, Any?>?        // generated as Map<? extends String, ?>
}
```
Built-in types: `Boolean Char Byte Short Int Long Float Double` (non-null → Java primitives, `?` → boxed), `String`,
`Any`, `Array<T>`, `List/Set/Collection/Iterable/Map` (and `Mutable*` variants). Other types must be imported or fully qualified.

## Annotations, doc comments, implements

```
import javax.validation.constraints.Size

/** Book with validation rules */
@SomeTypeAnnotation
input BookInput implements com.example.HasName {
    #allScalars
    @Size(min = 2, max = 50) name           // annotation on a mapped prop (replaces annotations copied from the entity)
    @Size(max = 200) remark: String         // annotation on a user prop
    @A authors @B { id }                    // @A on the property, @B on the generated nested type
}
```
- When a mapped prop has no annotation in the DTO, its entity annotations are copied (except Jimmer's own ORM annotations).
- Annotation arguments accept Java style (`{ }`) and Kotlin style (`[ ]`) arrays; `value` may be positional.
- `implements` adds interfaces to the generated class (and to nested types: `store implements X { ... }`); props that
  match interface methods get `@Override`.

## Input DTOs: null handling modes

For **nullable** props of input DTOs, the modifier decides how JSON `null` vs "property absent" map to the entity:

| Mode | JSON `"storeId": null` | JSON without `storeId` |
|---|---|---|
| `static` (default) | set `null` | set `null` |
| `fixed` | set `null` | **400 error** (property must be present) |
| `dynamic` | set `null` | leave unloaded (column untouched) |
| `fuzzy` | leave unloaded | leave unloaded (cannot clear a value) |

```
dynamic input BookUpdateInput {     // type-level default
    id!
    name
    fixed id(store)                 // prop-level override
}
```
Priority: prop modifier > type modifier > processor option `jimmer.dto.defaultNullableInputModifier` > `static`.
`fixed`/`dynamic` require Jackson deserialization (`@RequestBody`, Spring's default Jackson converter); the generated
class uses a Jackson builder to track which properties were present.

## Specifications (super QBE)

```
specification BookSpecification {
    like/i(name)                            // name ilike %value%      (options: i, ^, $ in this order: like/i^$)
    ge(price)                               // alias minPrice
    le(price)                               // alias maxPrice
    gt(price)  lt(price)                    // minPriceExclusive / maxPriceExclusive
    valueIn(edition) as editions            // Collection<Integer>; ne/notLike/valueIn/valueNotIn need an alias
    null(store) as noStore                  // boolean: store is null when true
    notNull(store) as hasStore
    associatedIdEq(store)                   // storeId  (same as id(store))
    associatedIdIn(store) as storeIds
    associatedIdNotIn(store) as excludedStoreIds
    flat(store) { like/i(name) as storeName }                 // reference → join
    flat(authors) { like/i(firstName, lastName) as authorName } // collection → EXISTS subquery; multi-prop = OR
}
```
- Every property is nullable; a `null` property adds no predicate (dynamic query for free).
- QBE functions: `eq ne gt ge lt le like notLike null notNull valueIn valueNotIn associatedIdEq associatedIdNe associatedIdIn associatedIdNotIn`.
- Multi-property arguments (OR) are allowed for `eq like null notNull valueIn associatedIdEq associatedIdIn` and require `as`.
- Use: `sqlClient.createQuery(t).where(spec).select(...)`; Spring MVC binds it from query parameters
  (`public Page<Book> find(BookSpecification spec)`).
- Combine: `JSpecification.and(s1, JSpecification.or(s2, s3))`, `JSpecification.not(s)` (nulls ignored).

## Output DTO association options

Placed immediately before an association in a **view** DTO; they configure the generated fetcher:

```
BookDetailView {
    #allScalars
    !fetchType(JOIN_ALWAYS)                 // SELECT | JOIN_IF_NO_CACHE | JOIN_ALWAYS (references)
    store { id name }
    !where(firstName ilike '%a%' or lastName ilike '%a%')   // =, <>, !=, <, <=, >, >=, like, ilike, is [not] null, and/or/()
    !orderBy(lastName asc, firstName asc)
    !batch(32)
    !limit(20)                              // !limit(limit, offset)
    authors { id firstName lastName }
}

BookFilteredView {
    !filter(com.example.AuthorFieldFilter)  // class implementing FieldFilter<AuthorTable>; replaces !where/!orderBy
    authors { id firstName }
}

TreeNodeView {
    !depth(2)                               // or !recursion(com.example.ChildNodesRecursionStrategy) → RecursionStrategy<TreeNode>
    childNodes*
}
```

```java
public class AuthorFieldFilter implements FieldFilter<AuthorTable> {
    @Override
    public void apply(FieldFilterArgs<AuthorTable> args) {
        args.where(args.getTable().firstName().ilike("a"));
        args.orderBy(args.getTable().lastName());
    }
}
public class ChildNodesRecursionStrategy implements RecursionStrategy<TreeNode> {
    @Override
    public boolean isRecursive(Args<TreeNode> args) { return !args.getEntity().name().equals("Clothing"); }
}
```

## Composition: for, fragments and reuse

```
package com.example.dto.catalog              // generated package + namespace; no export needed

fragment AuthorNameFields for com.example.model.Author {   // reusable property set, generates nothing
    firstName
    lastName
}

AuthorItem for com.example.model.Author {
    id
    #include(AuthorNameFields)
}

StoreItem for com.example.model.BookStore { id name }

BookCard for com.example.model.Book {
    id
    name
    !fetchType(JOIN_ALWAYS)
    store -> StoreItem                       // reuse an existing DTO type instead of a nested TargetOf_ class
    authors -> AuthorItem                    // List<AuthorItem>
}
```
- `->` targets must match the association's entity exactly; views reference views, inputs reference inputs,
  specifications reference specifications. Reuse cycles are rejected (use `*` recursion instead).
- Fragments are kind-neutral, validated where included, cannot contain `#types`, and can include other fragments.
  Duplicate property names from two contributors are compile errors.
- DTO source bundles: a library may ship `.dto` files as resources with marker `META-INF/jimmer/dto-bundle.properties`;
  consumers add it to `annotationProcessor` to generate their own classes.

## Polymorphic DTOs (#types)

For entity inheritance hierarchies:
```
export com.example.model.Client -> package com.example.dto

ClientView {
    id
    name
    #types {
        Organization { taxCode }
        Person { firstName lastName }
        // default class Other { ... }      // customize the catch-all branch; `Organization class Company { }` renames
    }
}

input ClientInput {
    id
    name
    #types {
        #exhaustive                          // every instantiable subtype must have a branch
        Organization { taxCode }
        Person { firstName lastName }
    }
}
```
Java: `ClientView` becomes an interface with nested implementations `ClientView.Organization`, `ClientView.Person`,
`ClientView.Default`; Jackson type info uses the discriminator.

## Generated Java code and usage

```java
// view
BookView view = new BookView(bookEntity);         // entity → DTO (entity must contain the shape)
Book entity = view.toEntity();                    // DTO → entity
BookView.METADATA.getFetcher();                   // fetcher matching the DTO shape
sqlClient.createQuery(t).select(t.fetch(BookView.class)).execute();
sqlClient.findById(BookView.class, id);

// input
BookInput input = new BookInput();                // JavaBean: setters/getters; nested classes BookInput.TargetOf_xxx
input.setName("SQL"); input.setStoreId(2L); input.setAuthorIds(List.of(1L, 2L));
sqlClient.save(input);                            // = sqlClient.save(input.toEntity())
Input.toMergedEntity(input1, input2);             // merge several inputs into one entity

// specification
BookSpecification spec = new BookSpecification();
spec.setName("graphql"); spec.setMinPrice(new BigDecimal(40));
sqlClient.createQuery(t).where(spec).select(t).execute();
```
Generated classes carry `@GeneratedBy(file = "...")`, `equals/hashCode/toString`, and validation-friendly getters.

## Build notes

- DTO compile errors point at the `.dto` file/line. A `.dto`-only change does **not** make `compileJava` out of date
  by default — add `tasks.named('compileJava') { inputs.files(fileTree('src/main/dto') { include '**/*.dto' }).withPathSensitivity(PathSensitivity.RELATIVE) }`
  (verified), or rebuild (`clean`, IntelliJ *Rebuild Module*).
- Processor options: `jimmer.dto.dirs`, `jimmer.dto.testDirs`, `jimmer.dto.defaultNullableInputModifier`,
  `jimmer.dto.fieldVisibility`, `jimmer.dto.hibernateValidatorEnhancement`, `jimmer.dto.bundle.enabled`.
- If no class in the module carries an annotation processed by jimmer-apt (`@Entity`, `@MappedSuperclass`,
  `@Embeddable`, `@Immutable`, `@ErrorFamily`), add `@EnableDtoGeneration` to any class.
