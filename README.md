# plugin-infrastructure

Shared infrastructure and utilities for Paper plugins. It provides a small runtime **module system**
for splitting a host plugin into independently-loaded feature modules, plus reusable **SQL schema
migration** and **formatting/parsing** helpers.

- **Java:** 21
- **Server API:** Paper `1.21.8` (compile-only)
- **Coordinates:** `com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT`

## Installation

Artifacts are published to `https://maven.democracycraft.net` — `/snapshots` for `-SNAPSHOT`
versions and `/releases` for stable ones.

```kotlin
// build.gradle.kts
repositories {
    maven("https://maven.democracycraft.net/snapshots") // use /releases for release versions
}

dependencies {
    implementation("com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT")
}
```

The Paper API is a `compileOnly` dependency here and is **not** brought in transitively — your plugin
already provides it at runtime.

## What's inside

| Package | Purpose |
|---------|---------|
| `com.minecraftcitiesnetwork.pluginInfrastructure.modules` | Load, initialize, reload, and shut down feature modules bundled as jars. |
| `com.minecraftcitiesnetwork.pluginInfrastructure.sql` | Ordered, versioned SQL schema migrations against a JDBC `DataSource`. |
| `com.minecraftcitiesnetwork.pluginInfrastructure.configurate` | MiniMessage message store loaded from Configurate, plus `TypeSerializer`s for `Component` and `SimpleDateFormat`. |
| `com.minecraftcitiesnetwork.pluginInfrastructure.util` | Small stateless helpers: duration parsing, currency and date formatting. |

## Module system

A **module** is a unit of plugin functionality that lives in its own jar under a directory, described
by a `ModuleManifest` and loaded at runtime by its own `URLClassLoader`.

- `PluginModule<T extends Plugin>` — the contract: `initialize`, `shutdown`, and an optional
  `reload` (default no-op) returning a `CompletableFuture<Void>`.
- `SimplePluginModule<T>` — a base class that tracks the host plugin and registered `Listener`s, with
  `registerListener` / `unregisterListeners` helpers.
- `ModuleManifest` — `moduleName`, `entryClass`, `author`, `expectedPluginClass`, `reloadable`
  (configurate-serializable).
- `ModuleLoader` — reads module jars from a directory and instantiates their entry classes.
- `ModuleLifecycleManager<T>` — owns the runtime lifecycle: `start()`, `stop()`,
  `reloadAsync(moduleName)`, `getActiveModules()`.

> **Reload semantics:** a module reload is a **config refresh** (`PluginModule#reload`), not a jar
> unload/reswap. Changed module code is only picked up by restarting the host plugin. Only modules
> whose manifest sets `reloadable = true` are refreshed.

> **Threading:** `ModuleLifecycleManager` is **not thread-safe** — drive it from a single thread (the
> server main thread on Paper). `reloadAsync` delegates to the module's own `reload`; any
> off-main-thread work is the module's responsibility.

```java
Path moduleDir = getDataFolder().toPath().resolve("modules");
ModuleLoader loader = new ModuleLoader(moduleDir);
var lifecycle = new ModuleLifecycleManager<>(this, loader, MyPlugin.class.getName(), getLogger());

lifecycle.start();                 // load + initialize all modules (call from the main thread)
// ...
lifecycle.reloadAsync("economy"); // refresh one reloadable module's config
// ...
lifecycle.stop();                  // shut down all modules
```

## SQL schema migrations

`SchemaMigrator` applies an ordered list of `MigrationStep`s against a JDBC `DataSource`, recording
progress in a version table. It is **abstract** and dialect-agnostic: a subclass supplies the three
bookkeeping statements.

- `MigrationStep(int version, String description, String resourcePath)` — one migration; its SQL is
  loaded as a classpath resource relative to a base directory.
- `SchemaMigrator` — implement `bootstrapDdl()`, `selectVersion()`, and `insertVersion()`, then call
  `migrate(dataSource, baseResourceDir, steps, resourceLoader, logger)`. It throws only
  `SQLException`, including for a missing or unreadable resource, so a plugin disabling itself on a
  failed migration has one thing to catch.

Migration SQL files load from the **caller-supplied `ClassLoader`**, so they live in the consuming
plugin's jar.

```java
class MariaSchemaMigrator extends SchemaMigrator {
    @Override protected String bootstrapDdl()  { return "CREATE TABLE IF NOT EXISTS myplugin_schema_version (...)"; }
    @Override protected String selectVersion() { return "SELECT COALESCE(MAX(version), 0) FROM myplugin_schema_version"; }
    @Override protected String insertVersion() { return "INSERT INTO myplugin_schema_version (version, description) VALUES (?, ?)"; }
}

var steps = List.of(new MigrationStep(1, "initial schema", "V1__initial.sql"));
new MariaSchemaMigrator().migrate(dataSource, Path.of("sql/migrations"), steps,
        getClass().getClassLoader(), getLogger());
```

> **Name the version table per plugin** — `myplugin_schema_version`, not `schema_version`. Two
> plugins sharing a database and a table name read each other's version: the one that is behind
> trips the "database is newer than this build" guard and refuses to start, and the one that is
> ahead re-applies migrations that were never its own.

> **Write every migration to be re-runnable.** Each step's statements and its version row share one
> transaction, but MySQL and MariaDB have no transactional DDL — an `ALTER TABLE` implicitly commits
> — so in practice that only protects the version row. Guard structural changes on
> `information_schema` lookups and make data fixes idempotent; a crash mid-step then leaves the
> recorded version unchanged and the step replays safely on the next start.

**Multi-statement files** work without any server-side flag. Statements are split client-side by
`splitStatements(String)` and executed one at a time, so `allowMultiQueries=true` need not appear in
the operator's JDBC arguments and each failure is reported against the statement that caused it. The
default splitter strips `--`, `#` and `/* */` comments and tracks quoted literals and backtick
identifiers, so a `;` inside a string is not a delimiter. It is `protected`: return `List.of(sql)` to
send each file to the server whole.

> **The default splitter lexes MySQL/MariaDB, not standard SQL** — `#` line comments, backtick
> identifiers, and backslash escapes inside literals are all dialect-specific. On PostgreSQL, SQLite
> or H2 you must override it. Only the three bookkeeping methods are abstract, so the compiler will
> not remind you about this one.

## Messages

`MessageContainer` holds MiniMessage templates keyed by dotted path, loads them from a Configurate
node tree, and renders them to Adventure `Component`s. A message stored under `prefix` is exposed to
every other message as `<prefix>`.

```java
container.load(loader.load());   // nested nodes flatten to "errors.no-permission"

sender.sendMessage(container.messageFor("punish.banned",
        MessageContainer.value("player", target.getName()),   // literal text, never markup
        MessageContainer.markup("time", container.messageFor("time.permanent"))));
```

> **Runtime values must go through `value(...)`.** It delegates to `Placeholder.unparsed`, so markup
> inside the value is inserted as text. MiniMessage's `Placeholder.parsed` re-parses its value *as
> markup*: text authored by one player, persisted, and re-rendered to another — a punishment reason,
> a shop description, a mail subject — could then carry `<click:run_command:'/op evil'>`, which runs
> as whoever clicks it. When a slot needs formatting, build a `Component` and inject it with
> `markup(...)`; a component is structured data and is never re-parsed.

There is deliberately no "render this arbitrary string as MiniMessage" helper. Concatenating runtime
values into `miniMessageFormattedFor(key)` and deserializing the result is the same hole with extra
steps.

`load` is a **reload, not a merge**: keys absent from the node tree are dropped, so a message deleted
from the file stops being served. Merge defaults and overrides at the `ConfigurationNode` level
(Configurate's `mergeFrom`) and load the result once. It never throws — an unreadable node is skipped
and its key renders as its own name in game, rather than one bad node costing every string in the
file.

## Utilities

- `DurationParserUtil.parse("2w5d12h30m10s")` → `Duration`. Units: `s`, `m`/`min`, `h`/`hr`, `d`,
  `w`/`wk`; combinable; case-insensitive; must be positive. The whole string must be
  `<digits><unit>` segments and nothing else — `"1h2y"` is rejected, not read as `"1h"`. Built for
  untrusted input: **every** rejection is an `IllegalArgumentException` (never a
  `NumberFormatException` or `ArithmeticException`), overflow is caught rather than wrapped, and the
  total is capped at ~1000 years.
- `DurationParserUtil.parseAllowingPermanent(input, keywords)` → `Optional<Duration>`. Empty means
  *permanent*, present means a real positive duration, and invalid input throws — three distinct
  outcomes, so no `-1` sentinel that a parse failure could masquerade as. The keywords are yours to
  choose; `DurationParserUtil.PERMANENT_KEYWORDS` is the conventional `perm`/`permanent` set, offered
  as a default rather than imposed, so a plugin whose commands say `forever` (or that reads the
  wording from a language file) is not stuck with English constants baked into shared infrastructure.
- `CurrencyFormatter.format(double)` → `"1,234.56"` (`#,##0.00`). Thread-safe.
- `DateFormatter.format(DateFormat, LocalDateTime)` → formats in the JVM default time zone. Note
  `DateFormat` is not thread-safe; do not share one instance across threads.

## Building

```bash
./gradlew build            # compile + assemble jar and sources jar, and run the tests
./gradlew test             # tests only
./gradlew publishToMavenLocal   # install to ~/.m2 for local testing
```

Requires a JDK 21 toolchain (Gradle will provision one if configured).

## Publishing

Publishing is configured via the `maven-publish` plugin. The deploy URL defaults to
`https://maven.democracycraft.net/{snapshots,releases}` based on the project version; only
credentials need to be supplied (a `deployUrl` Gradle property can override the URL if needed):

```bash
./gradlew publish -PdeployUsername=USER -PdeployPassword=TOKEN
```

### Continuous integration

Two GitHub Actions workflows live in `.github/workflows/`:

- **`build.yml`** — on push to `main`/`master`/`develop`, on PRs, and manual dispatch: runs
  `./gradlew build` and uploads the resulting jar as an artifact.
- **`deploy.yml`** — on a published release or manual dispatch: publishes to the Maven repository.
  The base URL comes from the repository **variable** `MAVEN_REPOSITORY_URL`, and the workflow
  appends `/snapshots` or `/releases` based on the version. Credentials come from the **secrets**
  `MAVEN_REPOSITORY_USERNAME` and `MAVEN_REPOSITORY_PASSWORD`.
