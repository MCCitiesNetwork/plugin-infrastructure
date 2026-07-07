# AGENTS.md

Guidance for AI coding agents working in **plugin-infrastructure** — a shared library of
infrastructure and utilities for Paper plugins. Keep changes small, faithful to existing style, and
verified.

## Project shape

- Single-module Gradle build (Kotlin DSL). Root: `build.gradle.kts`, `settings.gradle.kts`.
- Java **21** (`java` toolchain). Plugins: `java-library`, `maven-publish`.
- Version catalog: `gradle/libs.versions.toml`. `group`/`version` live in `gradle.properties`.
- Source root: `src/main/java/net/democracrycraft/pluginInfrastructure/`
  - `modules/` — runtime module system (load/init/reload/shutdown feature jars).
  - `sql/` — `SchemaMigrator` (abstract) + `MigrationStep` for versioned schema migrations.
  - `util/` — stateless helpers (`DurationParserUtil`, `CurrencyFormatter`, `DateFormatter`).
- No tests yet. There is no `src/test` source set configured.

## Build & verify

```bash
./gradlew build              # compile + assemble; the primary check
./gradlew compileJava        # faster compile-only loop while iterating
./gradlew publishToMavenLocal # sanity-check the published artifact shape
```

On Windows the shell is PowerShell; invoke `./gradlew` (the wrapper works from Git Bash too). Always
compile after a change — this is a library, so a clean `build` is the bar.

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
- **`ModuleLifecycleManager` is not thread-safe.** Its state must be touched from one thread (the
  server main thread on Paper).
- **`SchemaMigrator` is abstract and dialect-agnostic.** It provides no SQL of its own — subclasses
  implement `bootstrapDdl()`, `selectVersion()`, `insertVersion()`. Migration resources load from a
  caller-supplied `ClassLoader` (the consuming plugin's), not this library's.

## Porting code from sibling projects

Sibling plugins in the workspace are the source of much of this code. When lifting something in:

1. Repackage under `net.democracrycraft.pluginInfrastructure.*`.
2. Remove app-specific coupling (domain types, framework data sources) — generalize to JDK or
   Paper types so the result is a genuine reusable utility.
3. Leave behind app-specific data (e.g. a concrete migration list or schema SQL); this library
   ships mechanisms, not one app's content.
4. Fix latent issues exposed by the new shared context (notably thread-safety) and note the change.

## CI / publishing

- `.github/workflows/build.yml` — build + upload jar on push/PR/dispatch.
- `.github/workflows/deploy.yml` — publish on release/dispatch; `-SNAPSHOT` → `/snapshots`, else
  `/releases`. Base URL comes from the repository **variable** `MAVEN_REPOSITORY_URL`; credentials
  from **secrets** `MAVEN_REPOSITORY_USERNAME` / `MAVEN_REPOSITORY_PASSWORD`. These map to the
  `deployUrl`/`deployUsername`/`deployPassword` Gradle properties via `ORG_GRADLE_PROJECT_*` env.
- `build.gradle.kts` also defaults `deployUrl` to `https://maven.democracycraft.net/{snapshots,releases}`
  by version, so local `./gradlew publish -PdeployUsername=… -PdeployPassword=…` works without the URL.
- Publishing coordinates come from `gradle.properties`; bump `version` there.
