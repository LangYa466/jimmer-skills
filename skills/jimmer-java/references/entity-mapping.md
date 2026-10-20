# Entity mapping (Java)

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
- [Remote associations (microservices)](#remote-associations-microservices)
- [Naming strategy](#naming-strategy)
- [Jackson converters (@JsonConverter)](#jackson-converters-jsonconverter)

All ORM annotations are in `org.babyfish.jimmer.sql` except `@Formula` (`org.babyfish.jimmer.Formula`),
`@Immutable` (`org.babyfish.jimmer.Immutable`) and `@JsonConverter` (`org.babyfish.jimmer.jackson.JsonConverter`).

## Entity basics

```java
@Entity                         // ORM entity (interface!)
@Table(name = "BOOK")           // optional; default name comes from the naming strategy (BookStore -> BOOK_STORE)
public interface Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Column(name = "BOOK_NAME")   // only for non-association props; FK columns use @JoinColumn
    String name();

    @Nullable                      // org.jetbrains.annotations.Nullable recommended
    String description();
}
```

- Getters may be written `name()` (record style) or `getName()`; the property is `name` either way.
  Boolean `isXxx()` getters become property `xxx` unless the processor option `jimmer.keepIsPrefix=true`.
- `@Immutable` (non-ORM) creates immutable data types with drafts but no SQL DSL.
- An entity may only declare getters (abstract) or `default` methods used by `@Formula`.
- `@Entity(microServiceName = "...")` assigns a microservice (see remote associations).

## Nullity rules

Jimmer is strict about nullability; it drives SQL (`inner` vs `left` join), validation and DTO generation.

- primitives → non-null; boxed types → nullable; everything else → **non-null unless annotated with any
  annotation whose simple name is `Nullable` or `Null`**.
- `@Id` must be non-null (`long`, not `Long`). "No id yet" is expressed by leaving the id unloaded.
- `@OneToMany`/`@ManyToMany` lists are non-null (empty list = no rows).
- `@ManyToOne`/`@OneToOne` **must be nullable** when: mapped by a join table, backed by a fake FK, remote, the
  inverse side of `@OneToOne`, or the target entity has global filters (unless every filter of the target is an
  `AssociationIntegrityAssuranceFilter` and also applies to the source).
- `@ManyToOne(inputNotNull = true)` / `@OneToOne(inputNotNull = true)`: the property is nullable for queries but
  save commands reject null and database validation expects `NOT NULL`.

## Ids and generators

```java
@Id @GeneratedValue(strategy = GenerationType.IDENTITY) long id();                 // auto-increment
@Id @GeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "BOOK_ID_SEQ") long id(); // default seq: <TABLE>_ID_SEQ
@Id @GeneratedValue(generatorType = UUIDIdGenerator.class) UUID id();               // org.babyfish.jimmer.sql.meta.UUIDIdGenerator
@Id @GeneratedValue(generatorType = SnowflakeIdGenerator.class) long id();          // your UserIdGenerator<Long>
@Id @GeneratedValue(generatorRef = "snowflakeIdGenerator") long id();               // Spring bean name (multi-module)
@Id long id();                                                                       // assigned by application
```

```java
public class SnowflakeIdGenerator implements UserIdGenerator<Long> {   // generic arg must match the id type
    @Override public Long generate(Class<?> entityType) { return nextId(); }
}
```

- A generator class needs a public no-arg constructor (or use `generatorRef` / `setIdGenerator(...)` on the builder).
- Composite ids use an `@Embeddable` type as the id (avoid unless forced by a legacy schema).
- Snowflake ids exceed JavaScript's safe integers — add `@JsonConverter(LongToStringConverter.class)` to the id.

## Keys (@Key)

`@Key` declares the business unique key used by save commands to decide insert vs update when the id is absent.

```java
@Entity
@KeyUniqueConstraint            // a DB unique constraint covers the key (+ logical-deleted flag): enables SQL-level upsert
public interface TreeNode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) long id();
    @Key String name();
    @Key @Nullable @ManyToOne TreeNode parent();     // FK props may be part of the key
    @OneToMany(mappedBy = "parent") List<TreeNode> childNodes();
}
```

- Multiple keys: `@Key(group = "byCode")`; choose at runtime with `saveCommand(...).setKeyProps("byCode", ...)`.
- `@KeyUniqueConstraint(noMoreUniqueConstraints = true)` helps MySQL upserts for objects without id;
  `isNullNotDistinct = true` tells Postgres the constraint uses `NULLS NOT DISTINCT`.
- With logical deletion the DB unique constraint must include the logical-deleted column (e.g. `unique(NAME, DELETED_MILLIS)`),
  or use a partial unique index for boolean flags.

## Associations

### Many-to-one (foreign key, owning side)

```java
@Nullable
@ManyToOne                                  // FK column defaults to STORE_ID (naming strategy)
@JoinColumn(name = "STORE_ID")              // optional
BookStore store();
```

Via join table (legacy schemas; must be nullable): `@Nullable @ManyToOne @JoinTable BookStore store();`

### One-to-many (always the mirror of a many-to-one)

```java
@OneToMany(mappedBy = "store", orderedProps = {
        @OrderedProp("name"),
        @OrderedProp(value = "edition", desc = true)
})
List<Book> books();
```

Jimmer has **no unidirectional one-to-many**; `@JoinColumn`/`@JoinTable` are not allowed on `@OneToMany`.
`targetTransferMode` (`AUTO`/`ALLOWED`/`NOT_ALLOWED`) controls whether a parent may "steal" children from another parent when saving.

### One-to-one

```java
// owning side (FK in CUSTOMER table)
@OneToOne @JoinColumn(name = "ADDRESS_ID") Address address();
// inverse side — must be nullable, no @JoinColumn/@JoinTable
@Nullable @OneToOne(mappedBy = "address") Customer customer();
```

### Many-to-many

```java
@ManyToMany(orderedProps = @OrderedProp("firstName"))
@JoinTable(                                  // all optional: BOOK_AUTHOR_MAPPING(BOOK_ID, AUTHOR_ID) by default
        name = "BOOK_AUTHOR_MAPPING",
        joinColumnName = "BOOK_ID",
        inverseJoinColumnName = "AUTHOR_ID"
)
List<Author> authors();

// other side
@ManyToMany(mappedBy = "authors") List<Book> books();   // no @JoinTable with mappedBy
```

- Unlike JPA, the owning/mirror choice does **not** affect what save commands can persist — both sides work.
- Join table rows only contain the two FKs. If the middle table has business columns, map it as an entity
  (two `@ManyToOne`) and optionally add a `@ManyToManyView`.

## Real vs fake foreign keys, @OnDissociate

```java
@ManyToOne @JoinColumn(foreignKeyType = ForeignKeyType.REAL) BookStore store();   // DB has an FK constraint
@Nullable @ManyToOne @JoinColumn(foreignKeyType = ForeignKeyType.FAKE) BookStore store(); // no constraint; must be nullable
@ManyToMany
@JoinTable(
        joinColumns = @JoinColumn(name = "BOOK_ID", foreignKeyType = ForeignKeyType.FAKE),
        inverseJoinColumns = @JoinColumn(name = "AUTHOR_ID", foreignKeyType = ForeignKeyType.FAKE)
)
List<Author> authors();
```

`AUTO` (default) = real unless the dialect has no FKs (TiDB) or `setForeignKeyEnabledByDefault(false)` /
`jimmer.is-foreign-key-enabled-by-default=false`. Fake FKs make Jimmer verify referential integrity itself.

`@OnDissociate` (only on FK-based `@ManyToOne`) decides what happens to children that a parent no longer references
(during save with REPLACE, or when deleting the parent):

```java
@Nullable
@ManyToOne
@OnDissociate(DissociateAction.SET_NULL)   // NONE (default) | LAX | CHECK | SET_NULL | DELETE
BookStore store();
```

- `NONE`: uses global `default-dissociate-action-checking` (true → `CHECK`); fake FK + checking disabled → `LAX`.
- `LAX`: do nothing (DB cascade or dangling fake FK). `CHECK`: throw if children exist. `SET_NULL`: requires a nullable FK.
  `DELETE`: delete children (recursively applies their own rules).
- Override per command: `saveCommand(...).setDissociateAction(BookProps.STORE, DissociateAction.DELETE)`.

## @MappedSuperclass

```java
@MappedSuperclass
public interface BaseEntity {
    LocalDateTime createdTime();
    LocalDateTime modifiedTime();
    @Nullable @ManyToOne User createdBy();
}

@MappedSuperclass
public interface TenantAware { String tenant(); }

@Entity
public interface Book extends BaseEntity, TenantAware { ... }   // multiple inheritance is allowed
```

Generated `BaseEntityDraft`, `BaseEntityProps`, `TenantAwareProps` let you write one `DraftInterceptor` or global
`Filter` for every entity implementing the super type.

## Scalar types: enum, JSON, arrays, custom

**Enum** (stored by name by default):
```java
@EnumType(EnumType.Strategy.NAME)          // or ORDINAL; omit to use the global default
public enum Gender {
    @EnumItem(name = "M") MALE,            // stored as 'M'
    @EnumItem(name = "F") FEMALE
}
// ORDINAL variant: @EnumItem(ordinal = 100)
```

**JSON** (`@Serialized`, uses the configured `JsonCodec`; column type `jsonb` on Postgres, text elsewhere):
```java
@Serialized Map<String, List<Integer>> attributes();     // property level
@Serialized public class Settings { ... }                 // type level: every property of this type is JSON
```

**Arrays** (database must support arrays): `String[] tags();` — on Postgres add `@Column(sqlElementType = "text")`.

**UUID**: native where supported; otherwise register `ScalarProvider.uuidByString()` / `uuidByByteArray()`, or
`@Column(sqlType = "varchar")`.

**Custom scalar types** — implement `ScalarProvider<T, S>` (or extend `AbstractScalarProvider<T, S>`):
```java
public class PointProvider extends AbstractScalarProvider<Point, String> {
    @Override public Point toScalar(String sqlValue) { return Point.parse(sqlValue); }
    @Override public String toSql(Point value) { return value.toString(); }
}
// global: builder.addScalarProvider(new PointProvider()) (Spring: declare it as a bean)
// one property: builder.setScalarProvider(ShapeProps.CENTER, new PointProvider())
```
Mark the custom type (or property) with `@org.babyfish.jimmer.Scalar` when it is not otherwise recognized as a scalar.

## Embedded types (@Embeddable)

```java
@Embeddable
public interface Point { int x(); int y(); }

@Embeddable
public interface Rect {
    @PropOverride(prop = "x", columnName = "LEFT_X")
    @PropOverride(prop = "y", columnName = "TOP_Y")
    Point leftTop();
    @PropOverride(prop = "x", columnName = "RIGHT_X")
    @PropOverride(prop = "y", columnName = "BOTTOM_Y")
    Point rightBottom();
}

@Entity
public interface Transition {
    @Id long id();
    Rect source();                                                    // columns from Rect's overrides
    @PropOverride(prop = "leftTop.x", columnName = "TARGET_LEFT")     // nested path override
    @PropOverride(prop = "leftTop.y", columnName = "TARGET_TOP")
    @PropOverride(prop = "rightBottom.x", columnName = "TARGET_RIGHT")
    @PropOverride(prop = "rightBottom.y", columnName = "TARGET_BOTTOM")
    Rect target();
}
```

No `@Embedded` annotation exists. Embeddables cannot declare ids or associations. An embeddable id + multi-column
FK uses several `@JoinColumn(name = ..., referencedColumnName = ...)`.

## Defaults, versions, excluded props

```java
@Default("0") int state();                  // value used for INSERT when the prop is unloaded
@DatabaseDefault Instant createdAt();       // omitted from INSERT when unloaded so the DB default applies
@Version int version();                     // optimistic lock: UPDATE ... SET VERSION = VERSION + 1 WHERE VERSION = ?
@ExcludeFromAllScalars String passwordHash(); // not part of allScalarFields()/#allScalars; fetch explicitly
@DatabaseValidationIgnore String legacy();  // skip startup schema validation for this prop/type
```

## Logical deletion

```java
@LogicalDeleted("true")  boolean deleted();         // deleted=true, initial=false
@LogicalDeleted("false") boolean active();          // deleted=false, initial=true
@Default("0") @LogicalDeleted("1") int state();
@Default("INITIALIZED") @LogicalDeleted("DELETED") State state();
@LogicalDeleted long deletedMillis();               // deleted=currentTimeMillis, initial=0   (multi-version friendly)
@LogicalDeleted @Nullable UUID deletedData();       // deleted=random UUID, initial=null      (multi-version friendly)
@LogicalDeleted("now") @Nullable LocalDateTime deletedTime(); // deleted=now, initial=null     (multi-version friendly)
```

At most **one** `@LogicalDeleted` property per entity. Effects: deleting the entity issues an `UPDATE`; every query (and association fetch) automatically adds
`where <flag> <> deleted`. Custom values: `@LogicalDeleted(generatorType = MyGen.class)` /
`generatorRef = "beanName"` implementing `LogicalDeletedValueGenerator<T>`.
Join tables can have their own flag: `@JoinTable(logicalDeletedFilter = @JoinTable.LogicalDeletedFilter(columnName = "DELETED"))`;
`@JoinTable(deletedWhenEndpointIsLogicallyDeleted = true)` physically deletes middle rows when an endpoint is
logically deleted. Query-time behavior (ignore / reverse) → see `references/filters-interceptors-events.md`.

## Calculated properties: @Formula and @Transient

```java
// Java-side formula: default method + dependencies (only usable in fetchers, not in SQL DSL)
@Formula(dependencies = {"firstName", "lastName"})
default String fullName() { return firstName() + ' ' + lastName(); }

@Formula(dependencies = "authors")               // can depend on associations ("authors.firstName" too)
default int authorCount() { return authors().size(); }

// SQL formula: abstract getter, %alias = current table alias; usable in fetchers AND the DSL (table.fullName())
@Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)")
String fullName();
```

Complex calculation with batch resolvers (avoid N+1):
```java
@Transient(BookStoreAvgPriceResolver.class)   // or @Transient(ref = "bookStoreAvgPriceResolver") for a Spring bean name
BigDecimal avgPrice();

@Transient(ref = "bookStoreNewestBooksResolver")
List<Book> newestBooks();                     // association-typed calculated prop: resolver returns ids

@Transient Object uiState();                  // no argument = plain non-persistent prop, ignored by ORM
```

```java
@Component
public class BookStoreAvgPriceResolver implements TransientResolver<Long, BigDecimal> { // <source id, value>
    private final JSqlClient sqlClient;
    public BookStoreAvgPriceResolver(JSqlClient sqlClient) { this.sqlClient = sqlClient; }

    @Override
    public Map<Long, BigDecimal> resolve(Collection<Long> ids) {     // called once per batch
        BookTable t = BookTable.$;
        return Tuple2.toMap(
                sqlClient.createQuery(t)
                        .where(t.storeId().in(ids))
                        .groupBy(t.storeId())
                        .select(t.storeId(), t.price().avgAsDecimal())
                        .execute()
        );
    }

    @Override
    public BigDecimal getDefaultValue() { return BigDecimal.ZERO; } // required for non-null props when ids are missing
}
// For List<Book> newestBooks the resolver type is TransientResolver<Long, List<Long>> (entity -> its id type).
```

- Without Spring, resolver classes need a no-arg constructor or a constructor taking `JSqlClient`.
- Do not use object fetchers inside resolvers. For caching: override `getParameterMapRef()` and the
  `getAffectedSourceIds(...)` callbacks (see `references/cache.md`).
- Experimental overload `resolve(Collection<ID> ids, TransientResolverContext ctx)` exposes the prop/connection.

## View properties: @IdView and @ManyToManyView

```java
@Nullable @ManyToOne BookStore store();
@IdView Long storeId();                     // name ends with "Id" → base prop inferred ("store"); nullity must match

@ManyToMany List<Author> authors();
@IdView("authors") List<Long> authorIds();
```

View props share data with the base prop: setting `storeId` sets `store` to an id-only object and vice versa.
Fetching `storeId()` loads `store` internally but serializes `storeId`. **You do not need `@IdView` for the DSL**
(`table.storeId()` always exists for FK references) **nor for DTOs** (`id(store)`) — add it only when the entity
itself must expose ids in JSON.

```java
@Entity public interface LearningLink {
    @Id long id();
    @ManyToOne Student student();
    @ManyToOne Course course();
    @Nullable Integer score();               // business column on the "middle table"
}
@Entity public interface Student {
    @Id long id();
    @OneToMany(mappedBy = "student") List<LearningLink> learningLinks();
    @ManyToManyView(prop = "learningLinks", deeperProp = "course")   // deeperProp optional if unambiguous
    List<Course> courses();                  // read-only view; save via learningLinks
}
```

## Join-table extras, @JoinSql, @MapsId

```java
// several associations sharing one join table, distinguished by a column
@ManyToMany
@JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
        filter = @JoinTable.JoinTableFilter(columnName = "TYPE", values = "VIP"))
List<Customer> vipCustomers();

@ManyToMany
@JoinTable(name = "SHOP_CUSTOMER_MAPPING", joinColumnName = "SHOP_ID", inverseJoinColumnName = "CUSTOMER_ID",
        readonly = true)                     // read-only association: never written by save commands
List<Customer> customers();
```
Other `@JoinTable` flags: `cascadeDeletedBySource/Target = true` (the DB cascades middle-table rows, so Jimmer
skips deleting them) and `schema`. `preventDeletionBySource/Target` exist on the annotation but have no effect in
0.12.4.

```java
// non-structural legacy mapping (comma separated ids); only with @ManyToMany; read-only in practice
@ManyToMany
@JoinSql("concatenation_string_contains(%alias.AUTHOR_IDS, %target_alias.ID)")
List<Author> authors();
```

```java
// shared primary key one-to-one: the FK columns ARE the id columns
@Entity public interface MessageDelivery {
    @Id long messageId();                    // a real @Id, not an @IdView
    String status();
    @OneToOne @MapsId @JoinColumn Message message();
}
// partial: @ManyToOne @MapsId("tenantId") @JoinColumn(name = "TENANT_ID", referencedColumnName = "ID") Tenant tenant();
```

## Entity inheritance

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)   // or JOINED (one table per entity type, same id)
public interface Client {
    @Id long id();
    @Discriminator @Column(name = "CLIENT_TYPE") String type();   // String or enum; read-only for saves
    String name();
}

@Entity @DiscriminatorValue("ORG")
public interface Organization extends Client { String taxCode(); }

@Entity                                                   // discriminator defaults to the simple type name "Person"
public interface Person extends Client { String firstName(); String lastName(); }
```

- `@Inheritance` only on the root; one id; a type with subtypes but no `@DiscriminatorValue` is abstract.
- JOINED: each subtype has `@Table`; `joinedTableDissociateAction = JoinedTableDissociateAction.LAX` when DB cascades.
- Root queries return the concrete subtype objects; see `references/query-dsl.md` / `references/save-command.md` for `TypeMatchMode`, `treatAs`, `forType`, `#types`.
- `@MappedSuperclass` is for property sharing only (no polymorphism). Table-per-class is not supported.

## Remote associations (microservices)

```java
@Entity(microServiceName = "book-service")   public interface Book { @ManyToMany List<Author> authors(); }
@Entity(microServiceName = "author-service") public interface Author { @ManyToMany(mappedBy = "authors") List<Book> books(); }
@MappedSuperclass(acrossMicroServices = true) public interface CommonEntity { LocalDateTime createdTime(); } // no associations allowed
```
Different `microServiceName` ⇒ remote association: fetched through `MicroServiceExchange` (Spring Cloud support in the starter),
cannot be joined in SQL, and reference props must be nullable. Configure `jimmer.micro-service-name`.

## Naming strategy

Default `DefaultDatabaseNamingStrategy.UPPER_CASE`: `BookStore`→`BOOK_STORE`, `firstName`→`FIRST_NAME`,
FK `store`→`STORE_ID`, join table `BOOK_AUTHOR_MAPPING(BOOK_ID, AUTHOR_ID)`, sequence `BOOK_ID_SEQ`.
Use `LOWER_CASE` for case-sensitive lower-case schemas: builder `.setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)`
or a Spring `@Bean DatabaseNamingStrategy`. Implement `DatabaseNamingStrategy` for anything else.

## Jackson converters (@JsonConverter)

```java
@Id @JsonConverter(LongToStringConverter.class) long id();     // serialize as "123"; also applied to @IdView and DTOs

public class PhoneMaskConverter implements Converter<String, String> {   // org.babyfish.jimmer.jackson.Converter
    @Override public String output(String value) { return value.substring(0, 3) + "****" + value.substring(7); }
    @Override public String input(String value) { return value; }       // default throws (output-only)
}
@JsonConverter(PhoneMaskConverter.class) String phone();
```
Converters never receive `null`. Prefer `@JsonConverter` over Jackson annotations because DTO generation and client
code generation understand it.
