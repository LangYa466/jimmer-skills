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
    runtimeOnly("org.babyfish.jimmer:jimmer-client-swagger:0.12.4")           // optional: offline Swagger UI assets
    runtimeOnly("com.h2database:h2")                                          // or your JDBC driver
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}
```

KSP setup details (generated source dir, re-running KSP after `.dto` edits) are in setup-and-config. Put
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

All other `jimmer.*` properties (batch sizes, `id-only-target-checking-level`, `dissociation-logical-delete-enabled`,
`error-translator.*`, `client.ts.*`, ...) are the same as for Java — see the property table in the jimmer-java
skill's spring-boot reference, or `JimmerProperties` in the starter.

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
- `sqlClient.transaction { }` is for clients built with a Jimmer `TxConnectionManager`; under Spring use
  `@Transactional`/`TransactionTemplate`.
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
`saveCommand(...)`, `insert/insertIfAbsent/update(entity[, associatedMode])`, `deleteById(id[, DeleteMode])`,
`deleteByIds(...)`; reified `findView<BookView>(id)`, `findAllViews<BookView>()`, ...

- `PageParam` is `org.babyfish.jimmer.spring.repo.PageParam` (`byIndex(0, 10)` 0-based, `byNo(1, 10)` 1-based).
- Repository defaults: `save(e)` = `UPSERT` + `REPLACE`; `save(e, mode)` = `REPLACE` (unlike `sqlClient.save(e, mode)`,
  which defaults to `MERGE`); `insert` = `INSERT_ONLY` + `APPEND`; `update` = `UPDATE_ONLY` + `UPDATE`.
  There is no `save(entity, associatedMode)` overload — pass the mode too:
  `save(book, SaveMode.UPDATE_ONLY, AssociatedSaveMode.MERGE)`. `insert(input)`/`update(input)` are deprecated.

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
