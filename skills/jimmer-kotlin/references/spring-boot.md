# Spring Boot integration and client generation (Kotlin)

## Contents
- [Dependencies](#dependencies)
- [What the starter does](#what-the-starter-does)
- [application.yml](#applicationyml)
- [Customizing the KSqlClient](#customizing-the-ksqlclient)
- [Transactions](#transactions)
- [Repositories](#repositories)
- [REST controllers returning entities (@FetchBy)](#rest-controllers-returning-entities-fetchby)
- [Error families → typed client errors](#error-families--typed-client-errors)
- [OpenAPI, Swagger UI and TypeScript](#openapi-swagger-ui-and-typescript)
- [Spring GraphQL](#spring-graphql)
- [Multiple data sources](#multiple-data-sources)

## Dependencies

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"            // open classes for @Transactional/@Configuration proxies
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:0.12.4")   // includes jimmer-sql-kotlin
    ksp("org.babyfish.jimmer:jimmer-ksp:0.12.4")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.babyfish.jimmer:jimmer-client-swagger:0.12.4")           // required for the OpenAPI UI page (or -scalar)
    runtimeOnly("com.h2database:h2")                                          // or your JDBC driver
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}
```

KSP setup details (generated source dir, re-running KSP after `.dto` edits) are in `references/setup-and-config.md`. Put
`@EnableImplicitApi` (`org.babyfish.jimmer.client`) on the application class to export all controllers.

## What the starter does

- Creates the bean `sqlClient`. **Set `jimmer.language: kotlin`** to get a `KSqlClient`; otherwise it creates a
  `JSqlClient` and injecting `KSqlClient` fails. Not created if you declare your own `JSqlClient`/`KSqlClient` bean.
- Uses the Spring `DataSource` through a transaction-aware `ConnectionManager` (`@Transactional`, `JdbcTemplate` and
  Jimmer share connections).
- Detects the dialect from JDBC metadata unless `jimmer.dialect` (or a `Dialect` bean) is set.
- Registers beans of these types automatically: `KFilter`, `DraftInterceptor`, `DraftPreProcessor`,
  `ExceptionTranslator`, `ScalarProvider`, `CacheFactory`, `CacheOperator`, `CacheAbandonedCallback` (a
  `CacheAbandonedCallback.log()` bean is added when none exists), `KCustomizer`, `KInitializer`, `Executor`,
  `SqlFormatter`, `Dialect`, `DatabaseNamingStrategy`, `MetaStringResolver`, `UserIdGeneratorProvider`,
  `TransientResolverProvider`, `MicroServiceExchange`, ...
- Resolves `@Transient(ref = "beanName")` resolvers and `@GeneratedValue(generatorRef = "beanName")` from the context.
- Exposes OpenAPI/TypeScript endpoints when `jimmer.client.*` paths are set, and renders `@ErrorFamily` exceptions as
  JSON.

## application.yml

```yaml
jimmer:
  language: kotlin                    # required for KSqlClient
  show-sql: true
  pretty-sql: true
  # dialect: org.babyfish.jimmer.sql.dialect.PostgresDialect
  # trigger-type: TRANSACTION_ONLY
  # database-validation-mode: ERROR
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

All properties (`org.babyfish.jimmer.spring.cfg.JimmerProperties`):

| Property | Default | Meaning |
|---|---|---|
| `jimmer.language` | `java` | **must be `kotlin`** to create a `KSqlClient` |
| `jimmer.dialect` | auto-detected | e.g. `org.babyfish.jimmer.sql.dialect.PostgresDialect` |
| `jimmer.show-sql` / `pretty-sql` / `inline-sql-variables` | `false` | SQL log; `pretty-sql` requires `show-sql`, `inline-sql-variables` requires `pretty-sql` |
| `jimmer.executor-context-prefixes` | — | packages whose stack frames are appended to SQL logs |
| `jimmer.database-validation-mode` | `NONE` | `WARNING`/`ERROR`: compare entities with DB metadata at startup |
| `jimmer.trigger-type` | `BINLOG_ONLY` | `TRANSACTION_ONLY`, `BOTH` |
| `jimmer.default-reference-fetch-type` | `SELECT` | `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS`, `AUTO` |
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
| `jimmer.error-translator.debug-info-supported` / `debug-info-max-stack-trace-count` | `false` / `Integer.MAX_VALUE` | include messages/stack traces (never in production) |
| `jimmer.client.uri-prefix` | — | prefix added to generated client URLs |
| `jimmer.client.ts.path` | — | e.g. `/ts.zip`; enables TypeScript download |
| `jimmer.client.ts.api-name` / `indent` / `mutable` / `null-render-mode` / `is-enum-ts-style` | `Api` / `4` / `false` / `UNDEFINED` / `false` | TypeScript options |
| `jimmer.client.openapi.path` | — | e.g. `/openapi.yml`; enables the OpenAPI document |
| `jimmer.client.openapi.ui-path` | — | e.g. `/openapi.html`; enables the UI page |
| `jimmer.client.openapi.properties.*` | — | `info.title/description/version`, `servers`, `securities`, `components` … |
| `jimmer.client.java-feign.path` | — | registers a Feign-client download endpoint, but it throws `UnsupportedOperationException` in 0.12.4 |

## Customizing the KSqlClient

```kotlin
@Configuration
class JimmerConfig {

    @Bean
    fun jimmerCustomizer() = KCustomizer { dsl ->          // org.babyfish.jimmer.sql.kt.cfg.KCustomizer
        dsl.setDefaultBatchSize(64)
        dsl.setIdOnlyTargetCheckingLevel(IdOnlyTargetCheckingLevel.FAKE)
    }

    @Bean
    fun jimmerInitializer() = KInitializer { sqlClient ->  // runs once after the client is built
        sqlClient.triggers.addEntityListener(Book::class) { e -> /* ... */ }
    }
}
```

In Kotlin mode the starter **ignores Java `Filter`, `Customizer` and `Initializer` beans** (it only logs a warning) —
use `KFilter`, `KCustomizer` and `KInitializer`. (Language-neutral beans such as `DraftInterceptor`, `CacheFactory`
or `ScalarProvider` work in both modes.) To build a client yourself use
`SqlClients.kotlin(ctx[, dataSource][, block])` (`org.babyfish.jimmer.spring.SqlClients`): same properties and bean
auto-registration.

## Transactions

```kotlin
@Service
@Transactional
class BookService(private val sqlClient: KSqlClient) {
    fun save(input: BookInput): Book = sqlClient.save(input).modifiedEntity
}
```

- Run graph saves/deletes inside `@Transactional` — they issue several statements.
- `sqlClient.transaction { }` also works under the starter (its `SpringConnectionManager` runs the block through the
  context's `DataSourceTransactionManager`; it fails if there is none, e.g. JPA-only setups). Prefer
  `@Transactional`/`TransactionTemplate` for consistency.
- Without `kotlin("plugin.spring")`, `@Transactional` classes must be `open`.

## Repositories

**1. Class-based (recommended): `AbstractKotlinRepository<E, ID>`** (`org.babyfish.jimmer.spring.repo.support`)

```kotlin
@Repository
class BookRepository(sql: KSqlClient) : AbstractKotlinRepository<Book, Long>(sql) {

    fun findBooks(page: PageParam, name: String?, fetcher: Fetcher<Book>?): Page<Book> =   // org.babyfish.jimmer.Page
        createQuery {                                  // protected helper = sql.createQuery(Book::class) { }
            where(table.name `ilike?` name)
            orderBy(table.name.asc(), table.edition.desc())
            select(table.fetch(fetcher))
        }.fetchPage(page.index, page.size)

    fun findBySpec(pageable: Pageable, spec: BookSpecification, fetcher: Fetcher<Book>?) =
        createQuery {
            where(spec)
            orderBy(pageable.sort)                     // org.babyfish.jimmer.spring.repository.orderBy
            select(table.fetch(fetcher))
        }.fetchSpringPage(pageable)                    // org.babyfish.jimmer.spring.repository.fetchSpringPage

    fun avgPriceByStore(): Map<Long, BigDecimal> =
        executeQuery {                                 // createQuery { }.execute()
            where(table.storeId.isNotNull())
            groupBy(table.storeId)
            select(table.storeId.asNonNull(), avgAsDecimal(table.price).asNonNull())
        }.associate { it._1 to it._2 }

    fun raisePrice(storeId: Long, delta: BigDecimal): Int =
        executeUpdate {                                // also createUpdate/createDelete/executeDelete
            set(table.price, table.price + delta)
            where(table.storeId eq storeId)
        }
}
```

Inherited (`KotlinRepository`): `findById(id[, fetcher | ViewClass::class])`, `findByIds`, `findMapByIds`,
`findAll(fetcher) { asc(Book::name); desc(Book::edition) }`, `findPage(PageParam.byNo(1, 10), fetcher) { ... }`,
`findSlice(limit, offset, ...)`, `save(entity | input)`, `save(entity, mode[, associatedMode])`,
`saveCommand(...)`, `deleteById(id[, DeleteMode])`, `deleteByIds(...)`; `AbstractKotlinRepository` adds reified
`findView<BookView>(id)`, `findAllViews<BookView>()`, ...

- `PageParam` is `org.babyfish.jimmer.spring.repo.PageParam` (`byIndex(0, 10)` 0-based, `byNo(1, 10)` 1-based).
- Repository defaults: `save(e)` = `UPSERT` + `REPLACE`; `save(e, mode)` = `REPLACE` (unlike `sqlClient.save(e, mode)`,
  which defaults to `MERGE`). There is no `save(entity, associatedMode)` overload — pass the mode too:
  `save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.MERGE)`. The shortcuts `insert` (`INSERT_ONLY` + `APPEND`),
  `insertIfAbsent`, `update` (`UPDATE_ONLY` + `UPDATE`) and `merge` still exist but are all `@Deprecated`.

**2. Spring Data style: `KRepository<E, ID>`** (`org.babyfish.jimmer.spring.repository`)

```kotlin
interface BookKRepository : KRepository<Book, Long> {

    fun findByNameLikeIgnoreCaseAndPriceBetween(
        @DynamicParam name: String?,                   // null → predicate skipped
        @DynamicParam minPrice: BigDecimal?,
        @DynamicParam maxPrice: BigDecimal?,
        fetcher: Fetcher<Book>? = null                 // null fetcher = all table fields
    ): List<Book>

    fun findByStoreName(@DynamicParam storeName: String?, pageable: Pageable, fetcher: Fetcher<Book>? = null): Page<Book>

    fun countByName(name: String): Long

    fun findCheap(max: BigDecimal): List<Book> =       // custom DSL in a default method (`sql` property)
        sql.createQuery(Book::class) {
            where(table.price le max)
            select(table)
        }.execute()
}
```

- **A Kotlin nullable type is not enough**: without `@DynamicParam` a `null` argument throws
  `NullPointerException ... please annotate this parameter with @DynamicParam`.
- Repository interfaces must be under the application package or listed in `@EnableJimmerRepositories`.
- Spring `Sort` from strings: `SortUtils.toSort("name asc, edition desc")` (`org.babyfish.jimmer.spring.model`);
  apply in any query with `orderBy(sort)` (`org.babyfish.jimmer.spring.repository.orderBy`).

## REST controllers returning entities (@FetchBy)

```kotlin
@RestController
@RequestMapping("/book")
@Transactional
class BookController(private val bookRepository: BookRepository) {

    @GetMapping("/list")
    fun list(): List<@FetchBy("SIMPLE_FETCHER") Book> =
        bookRepository.findAll(SIMPLE_FETCHER) { asc(Book::name) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): @FetchBy("COMPLEX_FETCHER") Book? =     // nullable type = nullable result
        bookRepository.findById(id, COMPLEX_FETCHER)

    @GetMapping("/view/{id}")
    fun view(@PathVariable id: Long): BookView? = bookRepository.findById(id, BookView::class)

    @PutMapping
    @Throws(SaveException::class)                       // org.babyfish.jimmer.sql.exception.SaveException
    fun save(@RequestBody input: BookInput): @FetchBy("COMPLEX_FETCHER") Book =
        bookRepository.saveCommand(input).execute(COMPLEX_FETCHER).modifiedEntity

    companion object {                                  // fetchers live in the companion object
        private val SIMPLE_FETCHER = newFetcher(Book::class).by { name(); edition() }
        private val COMPLEX_FETCHER = newFetcher(Book::class).by {
            allScalarFields()
            store { name() }
            authors { firstName(); lastName() }
        }
    }
}
```

- `@FetchBy` (`org.babyfish.jimmer.client.FetchBy`) goes on the type use: `List<@FetchBy("F") Book>`,
  `Page<@FetchBy("F") Book>`, `@FetchBy("F") Book?`.
- The constant named in `@FetchBy` must be a companion-object property of the controller (or of `ownerType`).
- The fetcher you return must match the declared one — the generated client trusts it.
- Without `@EnableImplicitApi`, only classes/functions annotated with `@org.babyfish.jimmer.client.meta.Api` are
  exported; hide members with `@ApiIgnore`.

## Error families → typed client errors

```kotlin
@ErrorFamily                                            // org.babyfish.jimmer.error
enum class UserInfoErrorCode {
    @ErrorField(name = "illegalChars", type = Char::class, list = true)
    ILLEGAL_USER_NAME,
    PASSWORD_TOO_SHORT,
    PASSWORDS_NOT_SAME
}
```

KSP generates `abstract class UserInfoException : CodeBasedRuntimeException` with nested subclasses
(`UserInfoException.PasswordTooShort`, ...) and companion factories with named/default parameters:

```kotlin
@PostMapping("/user")
@Throws(UserInfoException::class)                       // declares the errors for OpenAPI/TypeScript
fun register(@RequestParam password: String) {
    if (password.length < 8) {
        throw UserInfoException.passwordTooShort("Password too short")
    }
    // UserInfoException.illegalUserName(message = "...", illegalChars = listOf('$'))
}
```

The starter renders `{"family":"USER_INFO","code":"PASSWORD_TOO_SHORT", ...fields}` with HTTP
`jimmer.error-translator.http-status` (500).

## OpenAPI, Swagger UI and TypeScript

- Endpoints exist only when `jimmer.client.openapi.path` / `ui-path` / `ts.path` are configured.
- `GET /ts.zip` contains `index.ts`, `Api.ts`, `ApiErrors.ts`, `Executor.ts`, `ElementOf.ts`, `RequestOf.ts`,
  `ResponseOf.ts`, `services/<Controller>.ts`, `model/dto/<Entity>Dto.ts` (one type per `@FetchBy` fetcher),
  `model/static/*.ts` (DTO classes) and `model/enums/*.ts`.
- Both endpoints stream; in `MockMvc` tests use `mvc.perform(asyncDispatch(mvc.perform(get("/openapi.yml")).andReturn()))`.
- KDoc on controllers, entities and DTOs becomes descriptions.

## Spring GraphQL

With `spring-boot-starter-graphql` on the classpath, the starter's `JimmerSpringGraphQLAutoConfiguration` wires every
entity into Spring GraphQL: each property gets a data fetcher, and every association and `@Transient`-resolver property
gets a batch loader. Clients can request any association or calculated property — no `@SchemaMapping`/`@BatchMapping`
code, no N+1. Only the root query is yours to write.

```graphql
# src/main/resources/graphql/schema.graphqls — type names = entity names, field names = property names
type Query { books(name: String): [Book!]! }
type Mutation { saveBook(input: BookInput!): Book! }
type Book { id: Int! name: String! edition: Int! price: Float! store: BookStore authors: [Author!]! }
type BookStore { id: Int! name: String! books: [Book!]! }
type Author { id: Int! firstName: String! lastName: String! }
input BookInput { name: String! edition: Int! price: Float! storeId: Int authorIds: [Int!]! }
```

```kotlin
@Controller
class BookGraphQLController(private val sqlClient: KSqlClient) {

    @QueryMapping
    fun books(@Argument name: String?, env: DataFetchingEnvironment): List<Book> =
        sqlClient.createQuery(Book::class) {
            where(table.name `ilike?` name)
            select(table.fetch(env.toFetcher<Book>()))    // org.babyfish.jimmer.spring.graphql.toFetcher: requested scalars only
        }.execute()

    @MutationMapping
    @Transactional
    fun saveBook(@Argument input: BookInput): Book =      // input DTO generated from a .dto file
        sqlClient.save(input).modifiedEntity
}
```

- Returning `select(table)` also works — associations are still loaded on demand — but loads every scalar column.
- `Long`, `BigDecimal`, `LocalDateTime` are not built-in GraphQL scalars. The schema above maps them to `Int`/`Float`
  (values are coerced); for exact types register custom scalars with a `RuntimeWiringConfigurer` bean
  (e.g. `graphql-java-extended-scalars`). Properties missing from the schema are simply not exposed.
- GraphQL cannot express recursive fetches (`` `childNodes*` ``); clients nest explicitly.

## Multiple data sources

```kotlin
// JTA: one client per data source
@Bean fun sqlClient1(ctx: ApplicationContext, @Qualifier("ds1") ds: DataSource) = SqlClients.kotlin(ctx, ds, null)

// Local transactions: a transaction manager per data source + a routing client
@Bean fun tm1(ctx: ApplicationContext, @Qualifier("ds1") ds: DataSource): PlatformTransactionManager =
    JimmerTransactionManager(SqlClients.kotlin(ctx, ds, null))      // org.babyfish.jimmer.spring.transaction
@Bean fun tm2(ctx: ApplicationContext, @Qualifier("ds2") ds: DataSource): PlatformTransactionManager =
    JimmerTransactionManager(SqlClients.kotlin(ctx, ds, null))
@Bean fun sqlClient(): KSqlClient = TransactionalSqlClients.kotlin()   // routes by @Transactional("tm1"/"tm2")
```

With the routing client every Jimmer call must run under `@Transactional("tm1")` or `@Transactional("tm2")`.
