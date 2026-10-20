# Spring Boot integration and client generation (Java)

## Contents
- [Dependencies](#dependencies)
- [What the starter does](#what-the-starter-does)
- [application.yml reference](#applicationyml-reference)
- [Customizing the JSqlClient](#customizing-the-jsqlclient)
- [Transactions](#transactions)
- [Repositories](#repositories)
- [REST controllers returning entities (@FetchBy)](#rest-controllers-returning-entities-fetchby)
- [Error families → typed client errors](#error-families--typed-client-errors)
- [OpenAPI, Swagger/Scalar UI and TypeScript](#openapi-swaggerscalar-ui-and-typescript)
- [Multiple data sources](#multiple-data-sources)
- [Spring Cloud / remote associations](#spring-cloud--remote-associations)

## Dependencies

```groovy
plugins {
    id 'org.springframework.boot' version '3.5.6'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'java'
}
dependencies {
    implementation "org.babyfish.jimmer:jimmer-spring-boot-starter:0.12.4"   // brings jimmer-sql, spring-jdbc, spring-data-commons
    annotationProcessor "org.babyfish.jimmer:jimmer-apt:0.12.4"
    implementation 'org.springframework.boot:spring-boot-starter-web'
    runtimeOnly "org.babyfish.jimmer:jimmer-client-swagger:0.12.4"           // optional: embedded Swagger UI assets
    // runtimeOnly "org.babyfish.jimmer:jimmer-client-scalar:0.12.4"        // optional: Scalar UI instead
    runtimeOnly 'com.h2database:h2'                                         // or your JDBC driver
}
```

Spring Boot 3.x uses Jackson 2 (`ImmutableModuleV2`); Spring Boot 4 uses Jackson 3 (`ImmutableModuleV3`) — the starter
registers the right module automatically. Modules without entities but with DTOs/controllers still need `jimmer-apt`
and `@EnableDtoGeneration` (Java) on some class; put `@EnableImplicitApi` on the application class for client export.

## What the starter does

- Creates a bean named `sqlClient`: `JSqlClient` (default) or `KSqlClient` when `jimmer.language=kotlin`. It is not
  created if you declare your own `JSqlClient`/`KSqlClient` bean.
- Uses the Spring `DataSource` through a Spring-aware `ConnectionManager`, so `@Transactional`, `JdbcTemplate` and Jimmer
  share connections and transactions.
- Detects the dialect from the JDBC metadata when `jimmer.dialect` is not set (declare a `Dialect` bean or set the
  property to be explicit).
- Registers every bean of these types automatically: `Filter`, `DraftInterceptor`, `DraftPreProcessor`,
  `ExceptionTranslator`, `ScalarProvider`, `PropScalarProviderFactory`, `CacheFactory`, `CacheOperator`,
  `CacheAbandonedCallback`, `Customizer`, `Initializer`, `Executor`, `SqlFormatter`, `JsonCodec`, `Dialect`,
  `DatabaseNamingStrategy`, `DatabaseSchemaStrategy`, `MetaStringResolver`, `UserIdGeneratorProvider`,
  `LogicalDeletedValueGeneratorProvider`, `TransientResolverProvider`, `MicroServiceExchange`.
- Resolves `@Transient(ref = "beanName")` resolvers, `@GeneratedValue(generatorRef = "beanName")` id generators and
  `@LogicalDeleted(generatorRef = ...)` from the Spring context.
- Exposes OpenAPI/TypeScript endpoints when the corresponding `jimmer.client.*` paths are configured, and translates
  `@ErrorFamily` exceptions into JSON error bodies.

## application.yml reference

| Property | Default | Meaning |
|---|---|---|
| `jimmer.language` | `java` | `kotlin` to create a `KSqlClient` |
| `jimmer.dialect` | auto-detected | e.g. `org.babyfish.jimmer.sql.dialect.PostgresDialect` |
| `jimmer.show-sql` / `pretty-sql` / `inline-sql-variables` | `false` | SQL log; `pretty-sql` requires `show-sql`, `inline-sql-variables` requires `pretty-sql` |
| `jimmer.executor-context-prefixes` | — | packages whose stack frames are appended to SQL logs |
| `jimmer.database-validation-mode` | `NONE` | `WARNING`/`ERROR`: compare entities with DB metadata at startup |
| `jimmer.trigger-type` | `BINLOG_ONLY` | `TRANSACTION_ONLY`, `BOTH` |
| `jimmer.default-reference-fetch-type` | `SELECT` | `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS` |
| `jimmer.max-join-fetch-depth` | `3` | |
| `jimmer.default-batch-size` / `default-list-batch-size` | `128` / `16` | fetcher batch sizes |
| `jimmer.default-enum-strategy` | `NAME` | `ORDINAL` |
| `jimmer.default-schema` | `""` | |
| `jimmer.jdbc.default-fetch-size` / `default-query-timeout` | — | JDBC statement settings |
| `jimmer.in-list-padding-enabled` / `expanded-in-list-padding-enabled` | `false` | pad `IN` lists for statement caching |
| `jimmer.offset-optimizing-threshold` | `Integer.MAX_VALUE` | deep-paging optimization threshold |
| `jimmer.reverse-sort-optimization-enabled` | `false` | reverse sort for last pages |
| `jimmer.is-foreign-key-enabled-by-default` | `true` | treat undeclared FKs as real |
| `jimmer.id-only-target-checking-level` | `NONE` | `FAKE`, `ALL` |
| `jimmer.default-dissociation-action-checkable` | `true` | `NONE` dissociation = `CHECK` |
| `jimmer.dissociation-logical-delete-enabled` | `false` | logically delete dissociated children |
| `jimmer.target-transferable` | `false` | allow re-parenting children in saves |
| `jimmer.default-type-change-allowed` | `false` | allow changing an inheritance subtype in saves |
| `jimmer.max-command-join-count` | `2` | |
| `jimmer.mutation-transaction-required` | `false` | fail mutations outside a transaction |
| `jimmer.default-save-returning-enabled` | `true` | use DML `returning` for save results |
| `jimmer.default-save-result-reads-all-properties` | `false` | re-read written values |
| `jimmer.explicit-batch-enabled` / `dumb-batch-acceptable` | `false` | MySQL JDBC batches |
| `jimmer.constraint-violation-translatable` | `true` | translate unique violations to `SaveException.NotUnique` |
| `jimmer.transaction-cache-operator-fixed-delay` | `5000` | ms between retries of deferred cache deletions (`TRANSACTION_ONLY`) |
| `jimmer.micro-service-name` | `""` | for remote associations |
| `jimmer.error-translator.disabled` | `false` | disable `@ErrorFamily` → JSON translation |
| `jimmer.error-translator.http-status` | `500` | HTTP status of translated errors |
| `jimmer.error-translator.debug-info-supported` / `debug-info-max-stack-trace-count` | `false` / `0` | include messages/stack traces (never in production) |
| `jimmer.client.uri-prefix` | — | prefix added to generated client URLs |
| `jimmer.client.ts.path` | — | e.g. `/ts.zip`; enables TypeScript download |
| `jimmer.client.ts.api-name` / `indent` / `mutable` / `null-render-mode` / `is-enum-ts-style` | `Api` / `4` / `false` / `UNDEFINED` / `false` | TypeScript options |
| `jimmer.client.openapi.path` | — | e.g. `/openapi.yml`; enables the OpenAPI document |
| `jimmer.client.openapi.ui-path` | — | e.g. `/openapi.html`; enables the UI page |
| `jimmer.client.openapi.properties.*` | — | `info.title/description/version`, `servers`, `securities`, `components` … |
| `jimmer.client.java-feign.path` / `api-name` / `indent` / `base-package` | — | download Spring Cloud Feign client code |

(Some older docs list `jimmer.save-command-pessimistic-lock` or `ts.anonymous`; they do not exist in 0.12.4.)

## Customizing the JSqlClient

```java
@Component
public class JimmerCustomizer implements Customizer {             // org.babyfish.jimmer.sql.runtime.Customizer
    @Override
    public void customize(JSqlClient.Builder builder) {
        builder.setDefaultBatchSize(64)
               .addScalarProvider(ScalarProvider.uuidByString())
               .setIdOnlyTargetCheckingLevel(IdOnlyTargetCheckingLevel.FAKE);
    }
}

@Component
public class JimmerInitializer implements Initializer {          // runs once after the client is built
    @Override
    public void initialize(JSqlClient sqlClient) {
        sqlClient.getTriggers().addEntityListener(Book.class, e -> { /* ... */ });
    }
}
```

To build the client yourself (e.g. several data sources) use `SqlClients.java(ctx[, dataSource][, builder -> ...])`
(`org.babyfish.jimmer.spring.SqlClients`): it applies the same properties and bean auto-registration.

## Transactions

The starter's `ConnectionManager` uses `DataSourceUtils`, so ordinary Spring transactions work:

```java
@Service
@Transactional
public class BookService {
    private final JSqlClient sqlClient;
    public BookService(JSqlClient sqlClient) { this.sqlClient = sqlClient; }

    public Book save(BookInput input) {
        return sqlClient.save(input).getModifiedEntity();
    }
}
```

- Run multi-statement saves/deletes inside `@Transactional` — a save graph issues several statements.
- `sqlClient.transaction(...)` (Jimmer's own API) is for clients configured with a Jimmer `TxConnectionManager`;
  under Spring use `@Transactional`/`TransactionTemplate`.
- Plain Spring without the starter: implement `ConnectionManager.execute` with `DataSourceUtils.getConnection` /
  `releaseConnection` (never `dataSource.getConnection()` directly).
- Native SQL that does not fit the DSL can use `JdbcTemplate` in the same transaction.

## Repositories

Two styles exist; prefer the explicit class style for new code.

**1. Class-based (`org.babyfish.jimmer.spring.repo`)** — a normal Spring bean with CRUD helpers and your own DSL code:

```java
@Repository
public class BookRepository extends AbstractJavaRepository<Book, Long> {

    private static final BookTable table = BookTable.$;

    public BookRepository(JSqlClient sql) { super(sql); }          // `sql` field is available to subclasses

    public Page<Book> findBooks(PageParam page, @Nullable String name, @Nullable Fetcher<Book> fetcher) {
        return sql.createQuery(table)
                .where(table.name().ilikeIf(name))
                .orderBy(table.name().asc(), table.edition().desc())
                .select(table.fetch(fetcher))
                .fetchPage(page.getIndex(), page.getSize());      // org.babyfish.jimmer.Page
    }
}

// inherited helpers (JavaRepository): findById(id[, fetcher | ViewClass]), findByIds, findMapByIds,
// findAll(fetcher | ViewClass, BookProps.NAME, BookProps.EDITION.desc()), findPage(PageParam.byNo(1, 10), ...),
// findSlice(limit, offset, ...), save/insert/update/merge(entity | input [, AssociatedSaveMode]),
// saveCommand(...), saveEntitiesCommand(...), deleteById(id[, DeleteMode]), deleteByIds(...)
```

`PageParam.byIndex(0, 10)` is 0-based, `PageParam.byNo(1, 10)` is 1-based. Repository `insert` = `INSERT_ONLY` +
`APPEND`, `update` = `UPDATE_ONLY` + `UPDATE`, `save` = `UPSERT` + `REPLACE`; `merge` is deprecated.

**2. Spring Data style (`org.babyfish.jimmer.spring.repository.JRepository<E, ID>`)** — interface proxies with derived
query methods. Associations can be referenced (`storeName` → `store.name`), and a `Fetcher<E>`/`Class<View>` parameter
controls the shape. **A parameter is only dynamic (null → predicate skipped) when annotated with `@DynamicParam`**;
otherwise a null argument throws `NullPointerException` (`@Nullable` alone is not enough in 0.12.4):

```java
public interface BookRepository extends JRepository<Book, Long> {

    List<Book> findByNameLikeIgnoreCaseAndPriceBetween(
            @DynamicParam @Nullable String name,
            @DynamicParam @Nullable BigDecimal minPrice,
            @DynamicParam @Nullable BigDecimal maxPrice,
            @Nullable Fetcher<Book> fetcher);                // null fetcher = all table fields

    Page<Book> findByStoreName(@DynamicParam @Nullable String storeName, Pageable pageable,
                               @Nullable Fetcher<Book> fetcher);      // org.springframework.data.domain.Page

    long countByName(String name);

    default List<Book> findCheap(BigDecimal max) {          // custom DSL in a default method
        BookTable t = BookTable.$;
        return sql().createQuery(t).where(t.price().le(max)).select(t).execute();
    }
}
```

Repository interfaces must live under the application package or be listed with `@EnableJimmerRepositories`. Use
`SortUtils.toSort("name asc, edition desc")` to build a Spring `Sort` from client strings, and
`SpringOrders.toOrders(table, sort)` to apply a `Sort` in the DSL.

## REST controllers returning entities (@FetchBy)

Return dynamic entities and declare their shape with `@FetchBy` on the **type use**; the generated OpenAPI/TypeScript
then contains one precise DTO per fetcher:

```java
@RestController
@RequestMapping("/book")
public class BookController {

    private static final Fetcher<Book> SIMPLE_FETCHER = BookFetcher.$.name().edition();
    private static final Fetcher<Book> COMPLEX_FETCHER = BookFetcher.$
            .allScalarFields()
            .store(BookStoreFetcher.$.name())
            .authors(AuthorFetcher.$.firstName().lastName());

    private final BookRepository bookRepository;

    public BookController(BookRepository bookRepository) { this.bookRepository = bookRepository; }

    @GetMapping("/list")
    public List<@FetchBy("SIMPLE_FETCHER") Book> list() {
        return bookRepository.findAll(SIMPLE_FETCHER);
    }

    @GetMapping("/{id}")
    public @FetchBy(value = "COMPLEX_FETCHER", nullable = true) Book get(@PathVariable long id) {
        return bookRepository.findById(id, COMPLEX_FETCHER);
    }

    @PutMapping
    public @FetchBy("COMPLEX_FETCHER") Book save(@RequestBody BookInput input) throws SaveException {
        return bookRepository.saveCommand(input).execute(COMPLEX_FETCHER).getModifiedEntity();
    }
}
```

- The constant must be a `static final` field of the controller (or of `ownerType = Xxx.class`).
- `@FetchBy` works anywhere in the type: `Page<@FetchBy("F") Book>`, `Map<String, @FetchBy("F") Book>`, fields of
  your own wrapper classes.
- The fetcher you *use* must match the one you *declare* — the generated client trusts it.
- Output DTO classes (`BookView`) need no `@FetchBy`; they are ordinary static types.
- Without `@EnableImplicitApi` (on any class, usually the application), only classes/methods annotated with
  `@org.babyfish.jimmer.client.meta.Api` are exported. Hide members with `@ApiIgnore`.

## Error families → typed client errors

```java
@ErrorFamily                                                       // org.babyfish.jimmer.error
public enum UserInfoErrorCode {
    @ErrorField(name = "illegalChars", type = Character.class, list = true)
    ILLEGAL_USER_NAME,
    PASSWORD_TOO_SHORT,
    PASSWORDS_NOT_SAME
}
```

`jimmer-apt` generates `UserInfoException` (suffix `ErrorCode`/`Error` is stripped) with factories
`UserInfoException.illegalUserName(message, illegalChars)`, `passwordTooShort(message)`, … Generated exceptions extend
`CodeBasedRuntimeException` by default (`CodeBasedException`, i.e. checked, with APT option
`jimmer.client.checkedException=true`).

```java
@PostMapping("/user")
public void register(@RequestBody UserInput input) throws UserInfoException {   // declares the errors for the client
    if (input.getPassword().length() < 8) {
        throw UserInfoException.passwordTooShort("Password too short");
    }
}
```

The starter's advice turns them into `{"family":"USER_INFO","code":"PASSWORD_TOO_SHORT", ...fields}` with
`jimmer.error-translator.http-status` (500). Declaring `throws SaveException` exports save errors (`NotUnique`,
`OptimisticLockError`, …) too. There is no `@ThrowsAll` in 0.12.

## OpenAPI, Swagger/Scalar UI and TypeScript

```yaml
jimmer:
  client:
    openapi:
      path: /openapi.yml
      ui-path: /openapi.html
      properties:
        info:
          title: Book Service
          version: 1.0
    ts:
      path: /ts.zip
```

- Endpoints are registered only when the paths are configured (servlet apps).
- Without `jimmer-client-swagger`, the UI page loads Swagger assets from unpkg; add the artifact for offline use.
- `GET /ts.zip` returns `index.ts`, `Api.ts`, `ApiErrors.ts`, `Executor.ts`, `services/<Controller>.ts`,
  `model/dto/<Entity>Dto.ts` (one type per `@FetchBy` fetcher, e.g. `BookDto['BookController/SIMPLE_FETCHER']`),
  `model/static/*.ts` (DTO classes, inputs) and `model/enums/*.ts`. In OpenAPI the shapes become schemas such as
  `Book_BookController_COMPLEX_FETCHER`; declared exceptions become `oneOf` error responses. Front-ends typically
  regenerate with a small script (download + unzip) or in CI.
- Both endpoints stream their body (`StreamingResponseBody`): in `MockMvc` tests use
  `mvc.perform(asyncDispatch(mvc.perform(get("/openapi.yml")).andReturn()))`.
- Javadoc/KDoc comments on controllers, entities, DTOs and props become descriptions (`@Description` for overrides).

## Multiple data sources

```java
// JTA (distributed transactions): one client per data source
@Bean public JSqlClient sqlClient1(ApplicationContext ctx, @Qualifier("ds1") DataSource ds) { return SqlClients.java(ctx, ds, null); }
@Bean public JSqlClient sqlClient2(ApplicationContext ctx, @Qualifier("ds2") DataSource ds) { return SqlClients.java(ctx, ds, null); }

// Local transactions: one transaction manager per data source + a routing client
@Bean public PlatformTransactionManager tm1(ApplicationContext ctx, @Qualifier("ds1") DataSource ds) {
    return new JimmerTransactionManager(SqlClients.java(ctx, ds, null));   // org.babyfish.jimmer.spring.transaction
}
@Bean public PlatformTransactionManager tm2(ApplicationContext ctx, @Qualifier("ds2") DataSource ds) {
    return new JimmerTransactionManager(SqlClients.java(ctx, ds, null));
}
@Bean public JSqlClient sqlClient() { return TransactionalSqlClients.java(); }  // routes by @Transactional("tm1"/"tm2")
```

With the routing client every method that touches Jimmer must run under `@Transactional("tm1")` or
`@Transactional("tm2")`.

## Spring Cloud / remote associations

Entities owned by another microservice are mapped with `@Entity(microServiceName = "...")` and associations to them
are fetched remotely (`jimmer.micro-service-name` names the current service; the starter exposes an exporter endpoint
and uses a `MicroServiceExchange`, by default `SpringCloudExchange` over a load-balanced `RestTemplate`). Remote
associations cannot be joined in SQL and must be `@Nullable` for many-to-one.
