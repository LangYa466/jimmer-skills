# Setup and configuration (Kotlin)

## Contents
- [Artifacts](#artifacts)
- [Gradle + KSP](#gradle--ksp)
- [Multi-module projects](#multi-module-projects)
- [KSP options](#ksp-options)
- [Creating KSqlClient without Spring](#creating-ksqlclient-without-spring)
- [Dialects](#dialects)
- [Connections and transactions](#connections-and-transactions)
- [SQL logging](#sql-logging)
- [Jackson / JSON](#jackson--json)
- [Database validation and DDL generation](#database-validation-and-ddl-generation)

## Artifacts

Group id `org.babyfish.jimmer`, one version for all artifacts (this skill targets `0.12.4`; latest release at
https://github.com/babyfish-ct/jimmer/releases).

| Artifact | Purpose |
|---|---|
| `jimmer-sql-kotlin` | Kotlin runtime API (`KSqlClient`), includes `jimmer-sql` and `jimmer-core` |
| `jimmer-ksp` | KSP processor: generates drafts, `XxxProps` (table DSL extensions), fetcher DSL, DTO classes from `.dto` files, client metadata, error types |
| `jimmer-spring-boot-starter` | Spring Boot auto-configuration (Java and Kotlin; set `jimmer.language=kotlin`) |
| `jimmer-client-swagger` / `jimmer-client-scalar` | optional OpenAPI UI assets |
| `jimmer-ddl-compiler` | optional KSP/APT processor generating DDL / Flyway scripts |

Kotlin projects must use **Gradle + KSP** (no kapt, no Maven support for KSP processors).

## Gradle + KSP

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"   // KSP version must match the Kotlin version prefix
}

val jimmerVersion = "0.12.4"

dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")  // or jimmer-spring-boot-starter
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
}

kotlin {
    // lets the IDE see generated code (Gradle builds work without it)
    sourceSets.main { kotlin.srcDir("build/generated/ksp/main/kotlin") }
}

ksp {
    arg("jimmer.dto.mutable", "true")          // optional, see KSP options
}

// IMPORTANT: KSP does not track src/main/dto; without this, editing ONLY .dto files keeps DTO classes stale
tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    val dtoDir = if (name == "kspKotlin") "src/main/dto" else "src/test/dto"
    inputs.files(fileTree(dtoDir) { include("**/*.dto") }).withPathSensitivity(PathSensitivity.RELATIVE)
}
```

Generated sources: `build/generated/ksp/main/kotlin`. With Spring add `kotlin("plugin.spring")`; the starter needs
`kotlin-reflect`.

## Multi-module projects

Apply KSP with `jimmer-ksp` in **every** module that needs generated code:

| Module contains | Why |
|---|---|
| `@Entity`/`@MappedSuperclass`/`@Embeddable`/`@Immutable` | drafts, `XxxProps.kt`, fetcher DSL |
| `src/main/dto/*.dto` | DTO classes (no extra annotation needed in Kotlin — unlike Java's `@EnableDtoGeneration`) |
| Spring controllers | OpenAPI/TypeScript metadata, `@FetchBy` checking |
| `@ErrorFamily` enums | generated exception classes |

## KSP options

Set with `ksp { arg("key", "value") }`.

| Option | Meaning |
|---|---|
| `jimmer.source.includes` / `jimmer.source.excludes` | package/type prefixes to (not) process |
| `jimmer.dto.dirs` / `jimmer.dto.testDirs` | DTO directories (default `src/main/dto`, `src/test/dto`) |
| `jimmer.dto.mutable` | `true` → DTO properties become `var` (default immutable `val` classes with `copy`) |
| `jimmer.dto.defaultNullableInputModifier` | `fixed` / `static` (default) / `dynamic` / `fuzzy` for nullable input props |
| `jimmer.dto.hibernateValidatorEnhancement` | Hibernate Validator friendly code |
| `jimmer.client.checkedException` | exceptions from `@ErrorFamily` extend `CodeBasedException` (checked) instead of `CodeBasedRuntimeException` |
| `jimmer.immutable.isModuleRequired` | also generate a `JimmerModule` file exposing `ENTITY_MANAGER` (rarely needed) |
| `jimmer.excludedUserAnnotationPrefixes` | annotation prefixes not copied to generated code |

## Creating KSqlClient without Spring

```kotlin
val sqlClient: KSqlClient = newKSqlClient {                     // org.babyfish.jimmer.sql.kt.newKSqlClient
    setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))  // supports transaction { }
    setDialect(PostgresDialect())
    setExecutor(Executor.log())
    setSqlFormatter(SqlFormatter.PRETTY)
    setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)   // default UPPER_CASE
    setDefaultEnumStrategy(EnumType.Strategy.NAME)
    setDefaultBatchSize(128)
    setDefaultListBatchSize(16)
    setDatabaseValidationMode(DatabaseValidationMode.ERROR)
    addFilters(TenantFilter())                                    // KFilter instances
    addDraftInterceptors(BaseEntityDraftInterceptor())
    addScalarProvider(ScalarProvider.uuidByString())
    setTriggerType(TriggerType.TRANSACTION_ONLY)
    setCacheFactory(cacheFactory)
}
val javaClient: JSqlClient = sqlClient.javaClient                  // underlying Java client if ever needed
val k2: KSqlClient = javaClient.toKSqlClient()
```

`KSqlClientDsl` mirrors `JSqlClient.Builder` with a few renamed members, e.g.
`setDefaultDissociateActionChecking(Boolean)` (Java: `setDefaultDissociateActionCheckable`). Others:
`setInListPaddingEnabled`, `setOffsetOptimizingThreshold`, `setReverseSortOptimizationEnabled`,
`setMaxJoinFetchDepth`, `setDefaultReferenceFetchType`, `setIdOnlyTargetCheckingLevel`, `setTargetTransferable`,
`setMutationTransactionRequired`, `setDefaultSaveReturningEnabled`, `setDefaultJdbcFetchSize/QueryTimeout`,
`setJsonCodec`, `setIdGenerator`, `setUserIdGeneratorProvider`, `setTransientResolverProvider`,
`addCustomizers`, `addInitializers`, `setZoneId`.

## Dialects

`org.babyfish.jimmer.sql.dialect.*`: `DefaultDialect`, `H2Dialect`, `MySqlDialect`, `MySql5Dialect`,
`PostgresDialect`, `OracleDialect`, `SqlServerDialect`, `SQLiteDialect`, `TiDBDialect`. The dialect decides upsert
SQL, paging, arrays (`= any(?)` on H2/Postgres), DML returning (H2/Postgres) and joins in update/delete.

## Connections and transactions

- `ConnectionManager.simpleConnectionManager(dataSource)` is transactional:

```kotlin
val id = sqlClient.transaction {                     // Propagation.REQUIRED
    sqlClient.save(book).modifiedEntity.id
}
sqlClient.transaction(Propagation.REQUIRES_NEW) { /* ... */ }
```

  A hand-written `ConnectionManager` (not a `TxConnectionManager`) cannot run `transaction { }`.
- With the Spring Boot starter, use `@Transactional`; Jimmer joins Spring transactions (same as `JdbcTemplate`).
- Every `execute()`, `fetchOne()`, `fetchPage()` … accepts an optional `con: Connection?` argument.

## SQL logging

- DSL: `setExecutor(Executor.log())`, `setSqlFormatter(SqlFormatter.PRETTY)`, `setExecutorContextPrefixes(listOf("com.example"))`.
- Spring Boot: `jimmer.show-sql`, `jimmer.pretty-sql`, `jimmer.inline-sql-variables`, `jimmer.executor-context-prefixes`.

## Jackson / JSON

```kotlin
val mapper = ObjectMapper()
    .registerModule(ImmutableModuleV2())      // org.babyfish.jimmer.jackson.v2 (Jackson 2); v3.ImmutableModuleV3 for Jackson 3
    .registerModule(JavaTimeModule())
    .registerKotlinModule()                   // jackson-module-kotlin, for your own Kotlin classes
val json = mapper.writeValueAsString(book)   // unloaded properties are omitted
val back = mapper.readValue(json, Book::class.java)
```

`toString()` of an entity is its JSON. Generated DTO classes carry `@JsonDeserialize(builder = ...)` and work with
plain Jackson. The starter registers the module automatically. In 0.12 the old `ImmutableModule` no longer exists.

## Database validation and DDL generation

- `setDatabaseValidationMode(DatabaseValidationMode.ERROR | WARNING | NONE)` (Spring: `jimmer.database-validation-mode`)
  checks tables/columns/foreign keys at startup; `@DatabaseValidationIgnore` skips a type/property.
- DDL / Flyway generation with KSP:

```kotlin
dependencies {
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ddl-compiler:$jimmerVersion")
}
ksp {
    arg("jimmerDdl.enabled", "true")
    arg("jimmerDdl.databaseType", "postgresql")
    arg("jimmerDdl.outputFormat", "flyway")
    arg("jimmerDdl.outputDir", "$projectDir/build/generated/jimmer-ddl/main/resources/db/migration")
    arg("jimmerDdl.version", "1001")
}
```
