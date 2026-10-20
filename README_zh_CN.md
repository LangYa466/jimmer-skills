# jimmer-skills

[English](README.md)

面向 [Jimmer](https://github.com/babyfish-ct/jimmer)（Java/Kotlin 编译期 ORM）的 Claude Skills，包含两个技能：

| 技能 | 适用 |
|---|---|
| [`jimmer-java`](skills/jimmer-java/SKILL.md) | Java 项目：`JSqlClient`、`jimmer-apt`、`BookTable`/`BookFetcher`/`Immutables` |
| [`jimmer-kotlin`](skills/jimmer-kotlin/SKILL.md) | Kotlin 项目：`KSqlClient`、`jimmer-ksp`、`Book { }` Draft、`fetchBy { }`、扩展属性 DSL |

两者均基于 **Jimmer 0.12.4**（Kotlin 2.1.20 / KSP 2.1.20-2.0.1，Spring Boot 3.5.6）。技能中的每一段代码都已在该版本上
编译并运行（见[验证](#验证)）。0.12.4 与官方文档不一致的行为（包括若干 bug）都在技能中明确标注。

## 安装

在 Claude Code 中：

```text
/plugin marketplace add LangYa466/jimmer-skills
/plugin install jimmer-skills@jimmer-skills
```

或在终端中：

```bash
claude plugin marketplace add LangYa466/jimmer-skills
claude plugin install jimmer-skills@jimmer-skills --scope user
```

编写 Jimmer 代码时技能会自动加载，也可以显式调用 `/jimmer-skills:jimmer-java`、`/jimmer-skills:jimmer-kotlin`。
不使用插件系统时，把技能目录复制到 `~/.claude/skills/`（或项目内的 `.claude/skills/`）即可。

## 内容

每个技能包含一个简短的 `SKILL.md`（核心概念、快速开始、关键规则、任务路由、常用模板）以及按需加载的参考文档：

| 参考文档 | 主题 |
|---|---|
| `setup-and-config` | Gradle/Maven（Java）或 Gradle + KSP（Kotlin）、处理器参数、不依赖 Spring 创建客户端、方言、事务、Jackson |
| `entity-mapping` | id 与生成器、`@Key`、关联、可空性、嵌入类型、JSON/枚举/ScalarProvider、公式、`@Transient` 解析器、`@IdView`、`@ManyToManyView`、逻辑删除、继承、远程关联 |
| `immutable-objects` | Draft、复制修改、`isLoaded`/`unload`/仅 id 对象、JSON、相等性 |
| `query-dsl` | 谓词、动态查询、连接、子查询、类型化元组、分组、原生 SQL、分页、关联表、弱连接、CTE/基础查询、继承查询 |
| `object-fetcher` | 对象抓取器、字段配置（batch/limit/filter/fetchType）、递归、IdView、DTO 投影、`find*` API |
| `dto-language` | `.dto` 文件：View、Input、Specification、宏、`id()`、`flat`、`fold`、空值处理模式、QBE、片段、`#types` |
| `save-command` | `SaveMode`/`AssociatedSaveMode`、按 key 匹配、脱钩、结果、upsert 掩码、条件更新、锁、异常转换 |
| `delete-and-dml` | 删除指令、update/delete 语句、基于查询的 insert/upsert、中间表 API |
| `filters-interceptors-events` | 全局过滤器、逻辑删除行为、`DraftInterceptor`/`DraftPreProcessor`、触发器、binlog |
| `cache` | 对象/关联/计算属性/多视图缓存、Redis + Caffeine、失效 |
| `spring-boot` | starter 配置、Repository、事务、`@FetchBy`、错误族、OpenAPI/TypeScript、多数据源 |
| `pitfalls` | 编译错误、运行时异常、静默错误结果、API 变化、代码审查清单 |

### 技能中记录的 0.12.4 重要行为

- **常量在左侧**的比较（`value(x).gt(col)`）会被渲染成取反的运算符（`col <= x`），务必把列写在左边。
- Kotlin 关联保存模式默认值不同：`save(e, mode)` 为 `MERGE`，`saveCommand(e, mode)` 与 Repository 的 `save(e, mode)` 为 `REPLACE`。
- `BinLog` 无法直接读取 `BigDecimal`/`BigInteger`，需要注册自定义 `BinLogPropReader`。
- `inIf(空集合)` / `` `valueIn?`(空集合) `` 不会被忽略，而是不匹配任何行；只有 `null` 会被忽略。
- 用 lambda 编写的 `ExceptionTranslator` 运行时报错。
- Spring Data 风格 Repository 的参数只有加 `@DynamicParam` 才是动态的。
- Kotlin 模式下 starter 会忽略 Java 的 `Filter`/`Customizer`/`Initializer` Bean。

## 验证

[`verification/`](verification) 中有四个 Gradle 项目，其测试覆盖了技能中的代码片段：

| 项目 | 内容 |
|---|---|
| `verification/java` | 纯 Java + H2：映射、查询、抓取器、DTO、保存、DML、过滤器、触发器、binlog、缓存 |
| `verification/java-spring` | Spring Boot starter（Java）：Repository、`@FetchBy`、错误族、OpenAPI、TypeScript |
| `verification/kotlin` | 纯 Kotlin + KSP + H2：Kotlin API 的同类主题 |
| `verification/kotlin-spring` | Spring Boot starter（Kotlin）：`AbstractKotlinRepository`、`KRepository`、`KCustomizer`、OpenAPI、TypeScript |

`verification/java` 与 `verification/kotlin` 中的 `behavior/KnownBehaviorTest` 固定了上面列出的 0.12.4 特殊行为（Repository/starter 相关行为由两个 Spring 项目断言）；升级 Jimmer 后如果这些测试失败，说明技能需要更新。
使用 Gradle 8.14+ 与 JDK 21 运行：

```bash
cd verification/kotlin && gradle test
```

CI 会在每次 push 和 pull request 时运行全部四个项目。

## 贡献

欢迎指正。请保持结论可验证：修改技能时，同时在 `verification/` 中添加或更新对应测试。

## 许可证

[MIT](LICENSE)
