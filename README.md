# plugin-infrastructure

Shared infrastructure and utilities for Paper plugins. It provides a small runtime **module system**
for splitting a host plugin into independently-loaded feature modules, plus reusable **SQL schema
migration** and **formatting/parsing** helpers.

- **Java:** 21
- **Server API:** Paper `1.21.8` (compile-only)
- **Coordinates:** `net.democracrycraft:plugin-infrastructure:1.0.0-SNAPSHOT`

## Installation

Artifacts are published to `https://maven.democracycraft.net` — `/snapshots` for `-SNAPSHOT`
versions and `/releases` for stable ones.

```kotlin
// build.gradle.kts
repositories {
    maven("https://maven.democracycraft.net/snapshots") // use /releases for release versions
}

dependencies {
    implementation("net.democracrycraft:plugin-infrastructure:1.0.0-SNAPSHOT")
}
```

The Paper API is a `compileOnly` dependency here and is **not** brought in transitively — your plugin
already provides it at runtime.

## What's inside

| Package | Purpose |
|---------|---------|
| `net.democracrycraft.pluginInfrastructure.modules` | Load, initialize, reload, and shut down feature modules bundled as jars. |
| `net.democracrycraft.pluginInfrastructure.sql` | Ordered, versioned SQL schema migrations against a JDBC `DataSource`. |
| `net.democracrycraft.pluginInfrastructure.util` | Small stateless helpers: duration parsing, currency and date formatting. |

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
progress in a `schema_version` table. It is **abstract** and dialect-agnostic: a subclass supplies
the three bookkeeping statements.

- `MigrationStep(int version, String description, String resourcePath)` — one migration; its SQL is
  loaded as a classpath resource relative to a base directory.
- `SchemaMigrator` — implement `bootstrapDdl()`, `selectVersion()`, and `insertVersion()`, then call
  `migrate(dataSource, baseResourceDir, steps, resourceLoader, logger)`.

Migration SQL files load from the **caller-supplied `ClassLoader`**, so they live in the consuming
plugin's jar. Multi-statement migration files require a `DataSource` configured for multi-statement
execution (for MariaDB, the `allowMultiQueries=true` connection property).

```java
class MariaSchemaMigrator extends SchemaMigrator {
    @Override protected String bootstrapDdl()  { return "CREATE TABLE IF NOT EXISTS schema_version (...)"; }
    @Override protected String selectVersion() { return "SELECT COALESCE(MAX(version), 0) FROM schema_version"; }
    @Override protected String insertVersion() { return "INSERT INTO schema_version (version, description) VALUES (?, ?)"; }
}

var steps = List.of(new MigrationStep(1, "initial schema", "V1__initial.sql"));
new MariaSchemaMigrator().migrate(dataSource, Path.of("sql/migrations"), steps,
        getClass().getClassLoader(), getLogger());
```

## Utilities

- `DurationParserUtil.parse("2w5d12h30m10s")` → `Duration`. Units: `s`, `m`/`min`, `h`/`hr`, `d`,
  `w`/`wk`; combinable; must be positive.
- `CurrencyFormatter.format(double)` → `"1,234.56"` (`#,##0.00`). Thread-safe.
- `DateFormatter.format(DateFormat, LocalDateTime)` → formats in the JVM default time zone. Note
  `DateFormat` is not thread-safe; do not share one instance across threads.

## Building

```bash
./gradlew build            # compile + assemble jar and sources jar
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
