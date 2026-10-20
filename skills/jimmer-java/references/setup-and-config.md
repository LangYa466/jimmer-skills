# Setup and configuration (Java)

## Contents
- [Artifacts](#artifacts)
- [Gradle](#gradle)
- [Maven](#maven)
- [Multi-module projects](#multi-module-projects)
- [Annotation processor options](#annotation-processor-options)
- [Creating JSqlClient without Spring](#creating-jsqlclient-without-spring)
- [Dialects](#dialects)
- [Connections and transactions](#connections-and-transactions)
- [SQL logging](#sql-logging)
- [Jackson / JSON](#jackson--json)
- [Database validation and DDL generation](#database-validation-and-ddl-generation)

## Artifacts

Group id: `org.babyfish.jimmer`. Use one version for all artifacts (latest release: see
https://github.com/babyfish-ct/jimmer/releases, strip the leading `v`; this skill targets `0.12.4`).

| Artifact | Purpose |
|---|---|
| `jimmer-sql` | Java runtime API (`JSqlClient`), includes `jimmer-core` |
| `jimmer-apt` | Annotation processor: generates Draft/Props/Table/Fetcher, compiles `.dto` files, exports API docs and error types |
| `jimmer-spring-boot-starter` | Spring Boot auto-configuration (contains both Java and Kotlin APIs) |
| `jimmer-client-swagger` / `jimmer-client-scalar` | Optional runtime UI assets for the generated OpenAPI page |
| `jimmer-ddl-compiler` | Optional processor that generates DDL / Flyway scripts from entities |
| `jimmer-mapstruct-apt` | Optional MapStruct integration for Jimmer drafts |
| `jimmer-bom` | BOM for version alignment |

## Gradle

```groovy
// build.gradle (Groovy DSL)
ext { jimmerVersion = '0.12.4' }

dependencies {
    implementation "org.babyfish.jimmer:jimmer-spring-boot-starter:${jimmerVersion}" // or jimmer-sql without Spring
    annotationProcessor "org.babyfish.jimmer:jimmer-apt:${jimmerVersion}"

    // Lombok MUST be declared as an annotation processor too once another processor exists
    compileOnly "org.projectlombok:lombok:${lombokVersion}"
    annotationProcessor "org.projectlombok:lombok:${lombokVersion}"
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs << "-Ajimmer.dto.defaultNullableInputModifier=static" // optional processor options
}

// IMPORTANT: without this, changing ONLY .dto files leaves compileJava UP-TO-DATE and DTO classes stale
tasks.named('compileJava') {
    inputs.files(fileTree('src/main/dto') { include '**/*.dto' })
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
```

```kotlin
// build.gradle.kts (Kotlin DSL, Java project)
val jimmerVersion = "0.12.4"
dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql:$jimmerVersion")
    annotationProcessor("org.babyfish.jimmer:jimmer-apt:$jimmerVersion")
}
```

Community Gradle plugin (optional): `id "tech.argonariod.gradle-plugin-jimmer"` with
`jimmer { version = "0.12.4" }` configures the processor automatically.

## Maven

```xml
<properties>
    <jimmer.version>0.12.4</jimmer.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.babyfish.jimmer</groupId>
        <artifactId>jimmer-sql</artifactId> <!-- or jimmer-spring-boot-starter -->
        <version>${jimmer.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <!-- keep Lombok here if you use it -->
                    <path>
                        <groupId>org.babyfish.jimmer</groupId>
                        <artifactId>jimmer-apt</artifactId>
                        <version>${jimmer.version}</version>
                    </path>
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>-Ajimmer.dto.defaultNullableInputModifier=static</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Kotlin developers must use Gradle (KSP); Java works with both Maven and Gradle.

## Multi-module projects

Run `jimmer-apt` in **every module that needs generated code**:

| Module contains | Why jimmer-apt is needed |
|---|---|
| `@Entity` / `@MappedSuperclass` / `@Embeddable` / `@Immutable` types | Draft, Props, Table, Fetcher, `Immutables`/`Tables`/`TableExes`/`Fetchers` |
| `src/main/dto/**/*.dto` files | DTO classes. **If the module has no entity, annotate any class with `@EnableDtoGeneration`** — otherwise the processor never runs |
| Spring Web controllers | OpenAPI / TypeScript metadata, `@FetchBy` validation, error-family export |
| `@ErrorFamily` enums | generated exception classes |

The entity module only needs `jimmer-core` at runtime if it must stay ORM-agnostic; generated code needs
`jimmer-sql` at compile time (`compileOnly`/`provided`).

```java
@EnableDtoGeneration      // org.babyfish.jimmer.sql.EnableDtoGeneration — Java only
@EnableImplicitApi        // org.babyfish.jimmer.client.EnableImplicitApi — export all controllers for client generation
@SpringBootApplication
public class App { public static void main(String[] args) { SpringApplication.run(App.class, args); } }
```

IDE notes: if IntelliJ does not see generated sources, mark the generated directory as *Generated Sources Root*.
When **only** `.dto` files changed, Gradle/IDE incremental compilation may not re-run the processor: register the DTO
directory as a `compileJava` input (see [Gradle](#gradle)), or rebuild (`gradle clean compileJava`, `mvn clean compile`,
IntelliJ *Rebuild Module*).

## Annotation processor options

Pass as `-Akey=value`.

| Option | Meaning |
|---|---|
| `jimmer.source.includes` / `jimmer.source.excludes` | Comma/semicolon separated package/type prefixes to (not) process |
| `jimmer.dto.dirs` / `jimmer.dto.testDirs` | DTO source dirs (default `src/main/dto`, `src/test/dto`) |
| `jimmer.dto.defaultNullableInputModifier` | `fixed` / `static` (default) / `dynamic` / `fuzzy` — default null handling of nullable input DTO props |
| `jimmer.dto.fieldVisibility` | Visibility of generated DTO fields |
| `jimmer.dto.hibernateValidatorEnhancement` | `true` to emit Hibernate Validator friendly code |
| `jimmer.dto.bundle.enabled` | `false` disables discovery of DTO source bundles on the processor path |
| `jimmer.entry.immutables` / `.tables` / `.tableExes` / `.fetchers` | Rename the generated summary classes (`Immutables`, `Tables`, `TableExes`, `Fetchers`) |
| `jimmer.keepIsPrefix` | `true` keeps the `is` prefix of boolean getters as part of the property name |
| `jimmer.client.checkedException` | `true` makes exceptions generated from `@ErrorFamily` checked (default unchecked) |

## Creating JSqlClient without Spring

```java
JSqlClient sqlClient = JSqlClient
        .newBuilder()
        .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource)) // supports sqlClient.transaction(...)
        .setDialect(new PostgresDialect())
        .setExecutor(Executor.log())                         // log SQL through slf4j
        .setSqlFormatter(SqlFormatter.PRETTY)
        .setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE) // default UPPER_CASE
        .setDefaultEnumStrategy(EnumType.Strategy.NAME)      // NAME (default) or ORDINAL
        .setDefaultBatchSize(128)                            // reference association batch size
        .setDefaultListBatchSize(16)                         // collection association batch size
        .setForeignKeyEnabledByDefault(true)                 // FKs without explicit type are real
        .setDatabaseValidationMode(DatabaseValidationMode.ERROR)
        .addFilters(new TenantFilter())                      // global filters
        .addDraftInterceptors(new BaseEntityDraftInterceptor())
        .addScalarProvider(ScalarProvider.uuidByString())   // custom scalar mapping
        .setIdGenerator(Book.class, myIdGenerator)           // programmatic id generator
        .setTriggerType(TriggerType.TRANSACTION_ONLY)        // BINLOG_ONLY (default) / TRANSACTION_ONLY / BOTH
        .setCacheFactory(cacheFactory)
        .build();
```

Other builder switches worth knowing: `setInListPaddingEnabled(true)` (pad `IN` lists to powers of two for plan
caching), `setOffsetOptimizingThreshold(n)` (deep-paging optimization), `setReverseSortOptimizationEnabled`,
`setMaxJoinFetchDepth`, `setDefaultReferenceFetchType(ReferenceFetchType.JOIN_IF_NO_CACHE)`,
`setIdOnlyTargetCheckingLevel(...)`, `setDefaultDissociateActionCheckable(...)`, `setTargetTransferable(...)`,
`setMutationTransactionRequired(...)`, `setDefaultSaveReturningEnabled(...)`, `setDefaultJdbcFetchSize/QueryTimeout`,
`setJsonCodec(...)`, `setSerializedTypeJsonCodec(Class, codec)`, `setSerializedPropJsonCodec(prop, codec)`,
`setUserIdGeneratorProvider`, `setTransientResolverProvider`, `setMicroServiceName/Exchange`,
`addCustomizers(...)`, `addInitializers(...)`, `setZoneId(...)`.

## Dialects

`org.babyfish.jimmer.sql.dialect.*`: `DefaultDialect`, `H2Dialect`, `MySqlDialect`, `MySql5Dialect`,
`PostgresDialect`, `OracleDialect`, `SqlServerDialect`, `SQLiteDialect`, `TiDBDialect`.
The dialect decides upsert SQL (`merge`/`on conflict`/`on duplicate key`), paging, `ilike`, arrays
(`= any(?)` for single-column `IN` on H2/Postgres), and whether update/delete may join.

## Connections and transactions

`ConnectionManager` decides how Jimmer borrows connections. Its single abstract method is
`<R> R execute(@Nullable Connection con, Function<Connection, R> block)`.

```java
// Spring without the starter: integrate with Spring transactions via DataSourceUtils
ConnectionManager cm = new ConnectionManager() {
    @Override
    public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
        if (con != null) {
            return block.apply(con);
        }
        Connection c = DataSourceUtils.getConnection(dataSource);
        try {
            return block.apply(c);
        } finally {
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }
};
```

- With the Spring Boot starter nothing is needed: Jimmer joins Spring-managed transactions (`@Transactional`).
- `sqlClient.transaction(...)` requires a `TxConnectionManager`: `ConnectionManager.simpleConnectionManager(dataSource)`
  and the starter's Spring connection manager are; a hand-written `ConnectionManager` like the one above is not
  (it throws `IllegalStateException`). Without Spring:

```java
Long id = sqlClient.transaction(() ->                      // Propagation.REQUIRED
        sqlClient.save(book).getModifiedEntity().id());
sqlClient.transaction(Propagation.REQUIRES_NEW, () -> { ...; return null; });
```
  Propagation values: `REQUIRED`, `REQUIRES_NEW`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER`, `MANDATORY`.
- Every `execute(...)`, `fetchOne(...)`, `fetchPage(...)`, `stream(...)` etc. also has an overload taking an explicit
  `java.sql.Connection`.
- Because Jimmer only needs a JDBC connection, it shares transactions with `JdbcTemplate` — use JdbcTemplate for
  raw report SQL, or native expressions inside the DSL.

## SQL logging

- Builder: `.setExecutor(Executor.log())` (`Executor.log(Level)`, `Executor.log(Logger)`), `.setSqlFormatter(SqlFormatter.PRETTY)`,
  `.setExecutorContextPrefixes(List.of("com.example.service"))` to print the business call site.
- Spring Boot: `jimmer.show-sql`, `jimmer.pretty-sql`, `jimmer.inline-sql-variables` (log only),
  `jimmer.executor-context-prefixes`.

## Jackson / JSON

Jimmer objects need Jimmer's Jackson module (unloaded props are skipped, visibility honored):

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new ImmutableModuleV2())   // org.babyfish.jimmer.jackson.v2 (Jackson 2.x)
        .registerModule(new JavaTimeModule());
String json = mapper.writeValueAsString(book);
Book back = mapper.readValue(json, Book.class);
```

- Jackson 3 (`tools.jackson`): use `org.babyfish.jimmer.jackson.v3.ImmutableModuleV3`.
- `toString()` of every Jimmer object is its JSON; `ImmutableObjects.fromString(Book.class, json)` parses.
- Jimmer abstracts JSON behind `org.babyfish.jimmer.jackson.codec.JsonCodec` (`JsonCodec.jsonCodec()` auto-detects
  Jackson 2/3). `@Serialized` columns, Redis caches and binlog parsing use it; configure with `setJsonCodec`,
  `setSerializedTypeJsonCodec`, `setSerializedPropJsonCodec`.
- Spring Boot starter registers `ImmutableModuleV2`/`V3` beans and a `JsonCodec` bean automatically. Do not
  replace Spring's Jackson message converter (input DTO `fixed`/`dynamic` modes depend on it).

## Database validation and DDL generation

- `setDatabaseValidationMode(DatabaseValidationMode.ERROR | WARNING | NONE)` (Spring: `jimmer.database-validation-mode`)
  checks tables/columns/foreign keys against entity metadata at startup. `@DatabaseValidationIgnore` skips a type/prop.
- `jimmer-ddl-compiler` (add next to `jimmer-apt` in `annotationProcessor`) generates DDL/Flyway scripts:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf(
        "-AjimmerDdl.enabled=true",
        "-AjimmerDdl.databaseType=postgresql",          // mysql, postgresql, h2, ...
        "-AjimmerDdl.outputFormat=flyway",
        "-AjimmerDdl.outputDir=$projectDir/build/generated/jimmer-ddl/main/resources/db/migration",
        "-AjimmerDdl.version=1001"
    ))
}
```
