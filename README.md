# jimmer-skills

[中文](README_zh_CN.md)

Claude skills for [Jimmer](https://github.com/babyfish-ct/jimmer), the compile-time ORM for Java and Kotlin. Two
skills are included:

| Skill | For |
|---|---|
| [`jimmer-java`](skills/jimmer-java/SKILL.md) | Java projects: `JSqlClient`, `jimmer-apt`, `BookTable`/`BookFetcher`/`Immutables` |
| [`jimmer-kotlin`](skills/jimmer-kotlin/SKILL.md) | Kotlin projects: `KSqlClient`, `jimmer-ksp`, `Book { }` drafts, `fetchBy { }`, extension-property DSL |

Both target **Jimmer 0.12.4** (Kotlin 2.1.20 / KSP 2.1.20-2.0.1, Spring Boot 3.5.6). Every code snippet in the
skills was compiled and run against that version — see [Verification](#verification). Behaviors where 0.12.4
differs from the official docs (including a few bugs) are called out in the skills.

## Install

In Claude Code:

```text
/plugin marketplace add LangYa466/jimmer-skills
/plugin install jimmer-skills@jimmer-skills
```

or from a shell:

```bash
claude plugin marketplace add LangYa466/jimmer-skills
claude plugin install jimmer-skills@jimmer-skills --scope user
```

The skills load automatically when you work on Jimmer code, or explicitly as `/jimmer-skills:jimmer-java` and
`/jimmer-skills:jimmer-kotlin`. To use a skill without the plugin system, copy its folder into
`~/.claude/skills/` (or `.claude/skills/` in a project).

## What the skills cover

Each skill has a short `SKILL.md` (mental model, quick start, critical rules, task router, templates) and focused
references that are loaded only when needed:

| Reference | Topics |
|---|---|
| `setup-and-config` | Gradle/Maven (Java) or Gradle + KSP (Kotlin), processor options, building the client without Spring, dialects, transactions, Jackson |
| `entity-mapping` | ids and generators, `@Key`, associations, nullity, embeddables, JSON/enums/scalar providers, formulas, `@Transient` resolvers, `@IdView`, `@ManyToManyView`, logical deletion, inheritance, remote associations |
| `immutable-objects` | drafts, copy-and-modify, `isLoaded`/`unload`/id-only objects, JSON, equality |
| `query-dsl` | predicates, dynamic queries, joins, subqueries, typed tuples, grouping, native SQL, paging, association tables, weak joins, CTE/base queries, inheritance queries |
| `object-fetcher` | fetchers, field config (batch/limit/filter/fetch type), recursion, id views, DTO projections, `find*` APIs |
| `dto-language` | `.dto` files: views, inputs, specifications, macros, `id()`, `flat`, `fold`, null-handling modes, QBE, fragments, `#types` |
| `save-command` | `SaveMode`/`AssociatedSaveMode`, key matching, dissociation, results, upsert masks, conditional updates, locking, exception translation |
| `delete-and-dml` | delete commands, update/delete statements, insert/upsert from a query, middle-table API |
| `filters-interceptors-events` | global filters, logical-deletion behavior, `DraftInterceptor`/`DraftPreProcessor`, triggers, binlog |
| `cache` | object/association/calculated/multi-view caches, Redis + Caffeine, invalidation |
| `spring-boot` | starter properties, repositories, transactions, `@FetchBy`, error families, OpenAPI/TypeScript, multiple data sources |
| `pitfalls` | compile errors, runtime exceptions, silent wrong results, API changes, review checklist |

### Notable 0.12.4 behaviors documented in the skills

- A comparison with the **constant on the left** (`value(x).gt(col)`) is rendered with the negated operator
  (`col <= x`); keep the column on the left.
- Kotlin association-mode defaults differ: `save(e, mode)` uses `MERGE`, `saveCommand(e, mode)` and repository
  `save(e, mode)` use `REPLACE`.
- `BinLog` cannot read `BigDecimal`/`BigInteger` values without a custom `BinLogPropReader`.
- `inIf(emptyList)` / `` `valueIn?`(emptyList) `` match no rows; only `null` is ignored.
- `ExceptionTranslator` written as a lambda fails at runtime.
- Spring Data style repository parameters are dynamic only with `@DynamicParam`.
- In Kotlin mode the starter ignores Java `Filter`/`Customizer`/`Initializer` beans.

## Verification

[`verification/`](verification) holds four Gradle projects whose tests exercise the snippets in the skills:

| Project | Content |
|---|---|
| `verification/java` | plain Java + H2: mapping, queries, fetchers, DTOs, saves, DML, filters, triggers, binlog, cache |
| `verification/java-spring` | Spring Boot starter (Java): repositories, `@FetchBy`, error families, OpenAPI, TypeScript |
| `verification/kotlin` | plain Kotlin + KSP + H2: the same topics for the Kotlin API |
| `verification/kotlin-spring` | Spring Boot starter (Kotlin): `AbstractKotlinRepository`, `KRepository`, `KCustomizer`, OpenAPI, TypeScript |

`verification/java` and `verification/kotlin` contain `behavior/KnownBehaviorTest`, which pins the surprising 0.12.4
behaviors listed above (the Spring projects assert the repository/starter ones); if one fails after a Jimmer upgrade,
the skills need updating. Run a project with Gradle 8.14+ and JDK 21:

```bash
cd verification/kotlin && gradle test
```

CI runs all four projects on every push and pull request.

## Contributing

Corrections are welcome. Please keep claims verifiable: add or update a test in `verification/` together with the
skill change.

## License

[MIT](LICENSE)
