# Entity mapping (Kotlin)

## Contents
- [Entity basics](#entity-basics)
- [Nullity rules](#nullity-rules)
- [Ids and generators](#ids-and-generators)
- [Keys (@Key)](#keys-key)
- [Associations](#associations)
- [Real vs fake foreign keys, @OnDissociate](#real-vs-fake-foreign-keys-ondissociate)
- [@MappedSuperclass](#mappedsuperclass)
- [Scalar types: enum, JSON, arrays, custom](#scalar-types-enum-json-arrays-custom)
- [Embedded types (@Embeddable)](#embedded-types-embeddable)
- [Defaults, versions, excluded props](#defaults-versions-excluded-props)
- [Logical deletion](#logical-deletion)
- [Calculated properties: @Formula and @Transient](#calculated-properties-formula-and-transient)
- [View properties: @IdView and @ManyToManyView](#view-properties-idview-and-manytomanyview)
- [Join-table extras, @JoinSql, @MapsId](#join-table-extras-joinsql-mapsid)
- [Entity inheritance](#entity-inheritance)
- [Remote associations](#remote-associations)
- [Naming strategy and JSON converters](#naming-strategy-and-json-converters)

All ORM annotations are in `org.babyfish.jimmer.sql` except `@Formula` (`org.babyfish.jimmer.Formula`),
`@Immutable` (`org.babyfish.jimmer.Immutable`) and `@JsonConverter` (`org.babyfish.jimmer.jackson.JsonConverter`).
Kotlin annotation arrays use `[...]`.

## Entity basics

```kotlin
@Entity                               // an interface with read-only `val` properties
@Table(name = "BOOK")                 // optional; default from the naming strategy (BookStore -> BOOK_STORE)
interface Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Column(name = "BOOK_NAME")       // scalar columns; FK columns use @JoinColumn
    val name: String

    val description: String?          // nullable column
}
```

- **One Jimmer type per file.** A `.kt` file may declare only one `@Entity`/`@MappedSuperclass`/`@Embeddable`/
  `@Immutable` type; otherwise KSP fails with "The File: X.kt declares several types decorated by ...".
- Only abstract `val`s plus `@Formula` properties with getters are allowed (no `var`, no functions with bodies used as props).
- `@Immutable` (non-ORM) gives drafts/copy without a SQL DSL.
- KSP generates `BookDraft`, `BookProps.kt` (table DSL extension properties), `BookFetcher.kt` (fetcher DSL) and
  the factory functions `Book { }`, `Book(base) { }`, `book.copy { }`. There are no `Immutables`/`Tables`/`Fetchers`
  summary classes in Kotlin.

## Nullity rules

Kotlin's type system is the nullity source: `String` is NOT NULL, `String?` is nullable.

- `@Id` must be non-null. "No id yet" = id unloaded.
- `List<...>` associations are non-null.
- A `@ManyToOne`/`@OneToOne` **must be nullable** when mapped by a join table, backed by a fake FK, remote, the
  inverse side of `@OneToOne`, or when the target has global filters (unless all those filters implement
  `KAssociationIntegrityAssuranceFilter` and also apply to the source).
- `@ManyToOne(inputNotNull = true)`: nullable for queries, but save commands reject `null`.

## Ids and generators

```kotlin
@Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long
@Id @GeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "BOOK_ID_SEQ") val id: Long
@Id @GeneratedValue(generatorType = UUIDIdGenerator::class) val id: UUID     // org.babyfish.jimmer.sql.meta.UUIDIdGenerator
@Id @GeneratedValue(generatorType = SnowflakeIdGenerator::class) val id: Long
@Id @GeneratedValue(generatorRef = "snowflakeIdGenerator") val id: Long      // Spring bean name
@Id val id: Long                                                             // assigned by the application
```

```kotlin
class SnowflakeIdGenerator : UserIdGenerator<Long> {      // type argument must match the id type
    override fun generate(entityType: Class<*>): Long = nextId()
}
```

Snowflake ids exceed JavaScript's safe integer range: annotate with `@JsonConverter(LongToStringConverter::class)`.

## Keys (@Key)

```kotlin
@Entity
@KeyUniqueConstraint                  // a DB unique constraint covers the key → native upsert possible
interface TreeNode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long
    @Key val name: String
    @Key @ManyToOne val parent: TreeNode?
    @OneToMany(mappedBy = "parent") val childNodes: List<TreeNode>
}
```

- Several keys: `@Key(group = "byCode")`; pick at runtime with `setKeyProps("byCode", Book::code)`.
- `@KeyUniqueConstraint(noMoreUniqueConstraints = true)` (MySQL upserts without id),
  `isNullNotDistinct = true` (Postgres `NULLS NOT DISTINCT`).
- With logical deletion, the unique constraint must include the logical-deleted column.

## Associations

```kotlin
@ManyToOne                                     // FK column STORE_ID by default
@JoinColumn(name = "STORE_ID")                 // optional
val store: BookStore?

@OneToMany(mappedBy = "store", orderedProps = [OrderedProp("name"), OrderedProp(value = "edition", desc = true)])
val books: List<Book>                          // always the mirror of a @ManyToOne; no unidirectional one-to-many

@ManyToMany(orderedProps = [OrderedProp("firstName"), OrderedProp("lastName")])
@JoinTable(name = "BOOK_AUTHOR_MAPPING", joinColumnName = "BOOK_ID", inverseJoinColumnName = "AUTHOR_ID")
val authors: List<Author>

@ManyToMany(mappedBy = "authors")
val books: List<Book>                          // other side, no @JoinTable

@OneToOne @JoinColumn(name = "ADDRESS_ID") val address: Address        // owning side
@OneToOne(mappedBy = "address") val customer: Customer?                // inverse side, nullable
```

Owning vs mirror side does not restrict save commands. A middle table with extra columns must be an entity
(two `@ManyToOne`) — optionally exposed with `@ManyToManyView`.

## Real vs fake foreign keys, @OnDissociate

```kotlin
@ManyToOne @JoinColumn(foreignKeyType = ForeignKeyType.FAKE) val store: BookStore?   // no DB constraint; nullable
@ManyToOne @OnDissociate(DissociateAction.SET_NULL) val store: BookStore?           // NONE|LAX|CHECK|SET_NULL|DELETE
```

`@OnDissociate` (only on FK-based many-to-one) decides what happens to children a parent no longer references
(`REPLACE` saves, deleting the parent): `NONE` → `CHECK` by default (or `LAX` for fake FKs when checking is off),
`LAX` do nothing, `CHECK` throw, `SET_NULL` clear the FK, `DELETE` delete children recursively.
Override per command: `setDissociateAction(Book::store, DissociateAction.DELETE)`.

## @MappedSuperclass

```kotlin
@MappedSuperclass
interface BaseEntity {
    val createdTime: LocalDateTime
    val modifiedTime: LocalDateTime
}

@MappedSuperclass
interface TenantAware { val tenant: String }

@Entity
interface Book : BaseEntity, TenantAware { /* ... */ }      // multiple inheritance allowed
```

Generated `BaseEntityDraft`/`TenantAwareProps` let one `DraftInterceptor` or `KFilter` cover all subtypes.

## Scalar types: enum, JSON, arrays, custom

```kotlin
@EnumType(EnumType.Strategy.NAME)              // or ORDINAL; omit for the global default
enum class Gender {
    @EnumItem(name = "M") MALE,                // stored as 'M'
    @EnumItem(name = "F") FEMALE
}

@Serialized val attributes: Map<String, List<Int>>   // JSON column via JsonCodec
@Serialized data class Settings(val theme: String, val pageSize: Int)  // type-level: every prop of this type is JSON

val tags: Array<String>                        // native array columns (Postgres: @Column(sqlElementType = "text"))
```

UUID columns: native where supported, else `addScalarProvider(ScalarProvider.uuidByString())`. Custom types:
extend `AbstractScalarProvider<T, S>` and register it (`addScalarProvider(...)`, or a Spring bean).

## Embedded types (@Embeddable)

```kotlin
@Embeddable
interface Point { val x: Int; val y: Int }

@Embeddable
interface Rect {
    @PropOverride(prop = "x", columnName = "LEFT_X")
    @PropOverride(prop = "y", columnName = "TOP_Y")
    val leftTop: Point
    @PropOverride(prop = "x", columnName = "RIGHT_X")
    @PropOverride(prop = "y", columnName = "BOTTOM_Y")
    val rightBottom: Point
}

@Entity
interface Transition {
    @Id val id: Long
    val source: Rect
    @PropOverride(prop = "leftTop.x", columnName = "TARGET_LEFT")
    @PropOverride(prop = "leftTop.y", columnName = "TARGET_TOP")
    @PropOverride(prop = "rightBottom.x", columnName = "TARGET_RIGHT")
    @PropOverride(prop = "rightBottom.y", columnName = "TARGET_BOTTOM")
    val target: Rect
}
```

No `@Embedded` annotation; embeddables have no id and no associations.

## Defaults, versions, excluded props

```kotlin
@Default("0") val state: Int                    // INSERT value when unloaded
@DatabaseDefault val createdAt: Instant          // omitted from INSERT when unloaded → DB default
@Version val version: Int                         // optimistic lock
@ExcludeFromAllScalars val passwordHash: String   // not in allScalarFields()/#allScalars
@DatabaseValidationIgnore val legacy: String?
```

## Logical deletion

```kotlin
@LogicalDeleted("true") val deleted: Boolean              // deleted=true, initial=false
@LogicalDeleted("false") val active: Boolean              // deleted=false, initial=true
@Default("0") @LogicalDeleted("1") val state: Int
@LogicalDeleted val deletedMillis: Long                   // deleted=currentTimeMillis, initial=0
@LogicalDeleted("now") val deletedTime: LocalDateTime?    // deleted=now, initial=null
```

At most **one** `@LogicalDeleted` property per entity. Deleting becomes an `UPDATE`; every query/fetch adds the
"not deleted" predicate. Custom values: `generatorType`/`generatorRef` with `LogicalDeletedValueGenerator<T>`.
Join tables: `@JoinTable(logicalDeletedFilter = JoinTable.LogicalDeletedFilter(columnName = "DELETED"))`,
`deletedWhenEndpointIsLogicallyDeleted = true`. Query-time behavior → `references/filters-interceptors-events.md`.

## Calculated properties: @Formula and @Transient

```kotlin
@Formula(dependencies = ["firstName", "lastName"])        // Kotlin-side: fetchers only, not usable in the DSL
val fullName: String
    get() = "$firstName $lastName"

@Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)")   // SQL formula: fetchers AND DSL (table.sqlFullName)
val sqlFullName: String

@Transient(BookStoreAvgPriceResolver::class)              // or @Transient(ref = "beanName") with Spring
val avgPrice: BigDecimal

@Transient(ref = "bookStoreNewestBooksResolver")
val newestBooks: List<Book>                               // association-typed: resolver returns ids
```

```kotlin
class BookStoreAvgPriceResolver(private val sqlClient: KSqlClient) : KTransientResolver<Long, BigDecimal> {

    override fun resolve(ids: Collection<Long>): Map<Long, BigDecimal> =
        sqlClient.createQuery(Book::class) {
            where(table.storeId valueIn ids)
            groupBy(table.storeId)
            select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
        }.execute().associate { it._1 to it._2 }

    override fun getDefaultValue(): BigDecimal = BigDecimal.ZERO   // for ids missing in the map (non-null prop)
}
```

Without Spring, resolvers need a no-arg constructor or one taking `KSqlClient`. For caching, override
`getParameterMapRef()` and `getAffectedSourceIds(...)` (`references/cache.md`).

## View properties: @IdView and @ManyToManyView

```kotlin
@ManyToOne val store: BookStore?
@IdView val storeId: Long?                        // base prop inferred from the name; nullity must match

@ManyToMany val authors: List<Author>
@IdView("authors") val authorIds: List<Long>
```

The DSL always has `table.storeId` for FK references and DTOs have `id(store)` — add `@IdView` only when the entity
itself should expose ids.

```kotlin
@Entity interface LearningLink {
    @Id val id: Long
    @ManyToOne val student: Student
    @ManyToOne val course: Course
    val score: Int?
}
@Entity interface Student {
    @Id val id: Long
    @OneToMany(mappedBy = "student") val learningLinks: List<LearningLink>
    @ManyToManyView(prop = "learningLinks", deeperProp = "course")
    val courses: List<Course>                    // read-only view; save through learningLinks
}
```

## Join-table extras, @JoinSql, @MapsId

```kotlin
@ManyToMany
@JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
    filter = JoinTable.JoinTableFilter(columnName = "TYPE", values = ["VIP"]))
val vipCustomers: List<Customer>

@ManyToMany
@JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID", readonly = true)
val customers: List<Customer>

@Entity interface MessageDelivery {               // shared primary key one-to-one
    @Id val messageId: Long
    val status: String
    @OneToOne @MapsId @JoinColumn val message: Message
}
```

`@JoinSql("...%alias...%target_alias...")` maps non-structural legacy relations (read-only, `@ManyToMany` only).
`cascadeDeletedBySource/Target = true` tells Jimmer the DB cascades middle-table rows.

## Entity inheritance

```kotlin
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)   // or JOINED
interface Client {
    @Id val id: Long
    @Discriminator @Column(name = "CLIENT_TYPE") val type: String
    val name: String
}

@Entity @DiscriminatorValue("ORG")
interface Organization : Client { val taxCode: String? }

@Entity                                                   // discriminator defaults to "Person"
interface Person : Client { val firstName: String?; val lastName: String? }
```

Single-table subtype columns must be nullable in the DB. Queries on the root return concrete subtype objects;
see `references/query-dsl.md` / `references/save-command.md` for `TypeMatchMode`, `treatAs`, `forType`.

## Remote associations

`@Entity(microServiceName = "book-service")` on each entity; associations between different services are fetched
through `MicroServiceExchange` (Spring Cloud in the starter), never joined in SQL, and references must be nullable.
`@MappedSuperclass(acrossMicroServices = true)` shares props across services.

## Naming strategy and JSON converters

Default `DefaultDatabaseNamingStrategy.UPPER_CASE` (`BookStore`→`BOOK_STORE`, `store`→`STORE_ID`,
`BOOK_AUTHOR_MAPPING`); use `setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)` or a custom
`DatabaseNamingStrategy`.

```kotlin
@Id @JsonConverter(LongToStringConverter::class) val id: Long

class PhoneMaskConverter : Converter<String, String> {        // org.babyfish.jimmer.jackson.Converter
    override fun output(value: String): String = value.take(3) + "****" + value.takeLast(4)
    override fun input(value: String): String = value
}
@JsonConverter(PhoneMaskConverter::class) val phone: String
```
