# AGENTS.md

Guidance for AI coding agents working in **plugin-infrastructure** — a shared library of
infrastructure and utilities for Paper plugins. Keep changes small, faithful to existing style, and
verified.

## Project shape

- Single-module Gradle build (Kotlin DSL). Root: `build.gradle.kts`, `settings.gradle.kts`.
- Java **21** (`java` toolchain). Plugins: `java-library`, `maven-publish`.
- Version catalog: `gradle/libs.versions.toml`. `group`/`version` live in `gradle.properties`.
- Source root: `src/main/java/com/minecraftcitiesnetwork/pluginInfrastructure/`
  - `modules/` — runtime module system (load/init/reload/shutdown feature jars).
  - `sql/` — `SchemaMigrator` (abstract) + `MigrationStep` for versioned schema migrations.
  - `configurate/` — `MessageContainer` plus `TypeSerializer`s for `Component` / `SimpleDateFormat`.
  - `util/` — stateless helpers (`DurationParserUtil`, `CurrencyFormatter`, `DateFormatter`).
- Tests live in `src/test/java`, JUnit 5 with Mockito (module lifecycle) and H2 (schema migrator).
  Migration fixtures are classpath resources under `src/test/resources/migrations/`.

## Build & verify

```bash
./gradlew build              # compile + assemble + test; the primary check
./gradlew test               # tests only
./gradlew compileJava        # faster compile-only loop while iterating
./gradlew publishToMavenLocal # sanity-check the published artifact shape
```

On Windows the shell is PowerShell; invoke `./gradlew` (the wrapper works from Git Bash too). Always
run `build` after a change — this is a library, so a clean `build`, tests included, is the bar.

Add tests with the change, not after it. Prefer a test that would have caught the specific bug over
one that restates the happy path, and assert what the code actually does — if a dependency turns out
to behave differently than expected (MiniMessage's default instance is lenient and does not throw on
a malformed tag, for one), pin the real behaviour and say so in the test, rather than asserting an
outcome that cannot occur.

## Conventions

- **Annotations:** use JetBrains `org.jetbrains.annotations.@NotNull` on params/returns, matching
  existing code. (It resolves transitively; it is compile-only and not a runtime dependency.)
- **Paper/Bukkit:** `compileOnly` only. Never add it as an `api`/`implementation` dependency — the
  host plugin provides it at runtime.
- **Prefer a lean API.** Favor default interface methods and small, focused types over new
  interfaces, exception hierarchies, or configuration knobs. When porting code in, strip
  dependencies that aren't essential (e.g. prefer a JDK `DataSource`/`DateFormat` parameter over a
  framework-specific type).
- **Thread-safety is explicit.** This library is consumed from both the main thread and async
  plugin threads. Document threading contracts in Javadoc, and don't share non-thread-safe state
  (`DecimalFormat`, `DateFormat`) across threads — see `CurrencyFormatter`'s `ThreadLocal`.
- Match the surrounding comment density and naming. Keep Javadoc on public/protected API.

## Domain notes (easy to get wrong)

- **Module reload = config refresh, not a jar swap.** `ModuleLifecycleManager.reloadAsync` calls the
  module's `PluginModule#reload`; it does **not** unload or replace the jar. Changed module code is
  only picked up by restarting the host plugin, and only manifests with `reloadable = true` refresh.
- **A failed `initialize` is never followed by `shutdown`.** The manager only logs and closes the
  class loader, so a module must clean up its own partial registrations before throwing (documented
  on `PluginModule#initialize`). `SimplePluginModule` tracks listeners but unregisters nothing by
  itself. Changing this is a contract change for every module, not a bug fix.
- **Module commands are declared, never unregistered.** Paper only accepts commands during its
  lifecycle commands event, before modules initialize, and its API cannot remove them. A
  `CommandDeclaringModule` declares through `ModuleLifecycleManager#declareCommands` between `load()`
  and `initializeLoaded()`; `CommandGuard` rebuilds each tree so executors and suggestions run only
  while the module is active, and the host's `ModuleUnavailableHandler` replies otherwise. Redirects
  are rejected because they would bypass the guard. A module never cleans up its commands.
- **`ModuleLifecycleManager` is not thread-safe.** Its state must be touched from one thread (the
  server main thread on Paper).
- **`SchemaMigrator` is abstract and dialect-agnostic.** It provides no SQL of its own — subclasses
  implement `bootstrapDdl()`, `selectVersion()`, `insertVersion()`. Migration resources load from a
  caller-supplied `ClassLoader` (the consuming plugin's), not this library's.
- **Version tables are namespaced per plugin.** Subclasses must not use a bare `schema_version`:
  two plugins sharing a database would read each other's version, and one of them would refuse to
  start while the other re-applied migrations that were never its own.
- **Migration files are split client-side.** `splitStatements` is quote- and comment-aware so a `;`
  inside a string literal is not a delimiter; statements are executed one at a time so failures are
  attributed correctly and no `allowMultiQueries=true` is required. Don't reintroduce whole-file
  execution as the default. Note the default splitter lexes **MySQL/MariaDB**, not standard SQL
  (`#` comments, backtick identifiers, backslash escapes) — it is `protected` so another dialect can
  replace it, and that is the one dialect concern the compiler won't prompt for.
- **Runtime values are never parsed as MiniMessage.** `MessageContainer.value(...)` wraps
  `Placeholder.unparsed`; `Placeholder.parsed` would let a stored, player-authored string carry
  `<click:run_command:…>` that runs as whoever clicks it. Do not add a helper that deserializes an
  arbitrary runtime string, and do not build templates by concatenating values into
  `miniMessageFormattedFor`. `markup(...)` with a pre-built `Component` is the way to get formatting
  into a slot.
- **User-facing parsers treat their input as hostile.** `DurationParserUtil` is fed straight from
  commands: every rejection path is an `IllegalArgumentException`, arithmetic is `Math.*Exact`, and
  case folding is `Locale.ROOT` (a Turkish default locale otherwise breaks the `min` unit). Keep
  that shape for anything else that parses player input.

## Porting code from sibling projects

Sibling plugins in the workspace are the source of much of this code. When lifting something in:

1. Repackage under `com.minecraftcitiesnetwork.pluginInfrastructure.*`.
2. Remove app-specific coupling (domain types, framework data sources) — generalize to JDK or
   Paper types so the result is a genuine reusable utility.
3. Leave behind app-specific data (e.g. a concrete migration list or schema SQL); this library
   ships mechanisms, not one app's content.
4. Fix latent issues exposed by the new shared context (notably thread-safety) and note the change.

## CI / publishing

- `.github/workflows/build.yml` — build + upload jar on push/PR/dispatch.
- `.github/workflows/deploy.yml` — publish on release/dispatch; `-SNAPSHOT` → `/snapshots`, else
  `/releases` on `https://maven.minecraftcitiesnetwork.com`; credentials from the **secrets**
  `MAVEN_USERNAME` / `MAVEN_PASSWORD`. These map to the
  `deployUrl`/`deployUsername`/`deployPassword` Gradle properties via `ORG_GRADLE_PROJECT_*` env.
- `build.gradle.kts` also defaults `deployUrl` to `https://maven.minecraftcitiesnetwork.com/{snapshots,releases}`
  by version, so local `./gradlew publish -PdeployUsername=… -PdeployPassword=…` works without the URL.
- Publishing coordinates come from `gradle.properties`; bump `version` there.
