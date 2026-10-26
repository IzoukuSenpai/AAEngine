# Contributing to AAEngine

## Clean-room rule

AAEngine is an independent implementation. Base your work only on public APIs, official documentation, the specification in `AAENGINE_RECONSTRUCTION.md` and runtime observation. Never commit copied or decompiled proprietary source code. Every pull request must confirm this in its checklist.

## Prerequisites

- JDK 25 (Temurin recommended)
- Gradle wrapper (included, `./gradlew`), no global Gradle needed

## Build commands

```bash
./gradlew build                 # compile, test, lint everything
./gradlew ktlintCheck           # style check only
./gradlew ktlintFormat          # auto-format Kotlin sources
./gradlew :engine-core:test     # run one module's tests
./gradlew :engine-paper:build   # produce the Paper plugin jar
```

The plugin jar is written to `engine-paper/build/libs/`.

## Module layout

All modules are flat at the repository root. Base package is `dev.aaengine.<module name without the engine- prefix, dashes removed>`.

| Module | Package | Role |
| --- | --- | --- |
| engine-api | dev.aaengine.api | Public API, no internal dependencies |
| engine-core | dev.aaengine.core | Core runtime, depends on api |
| engine-paper | dev.aaengine.paper | Paper plugin entry point, wires every module |
| engine-items | dev.aaengine.items | Custom items |
| engine-mechanics | dev.aaengine.mechanics | Mechanics |
| engine-blocks | dev.aaengine.blocks | Custom blocks |
| engine-furniture | dev.aaengine.furniture | Furniture |
| engine-recipes | dev.aaengine.recipes | Recipes |
| engine-pack | dev.aaengine.pack | Resource-pack compilation and publishing |
| engine-fonts | dev.aaengine.fonts | Glyphs, fonts, sounds |
| engine-version-common | dev.aaengine.versioncommon | Version bridges |
| engine-integrations | dev.aaengine.integrations | Third-party integrations |
| engine-testkit | dev.aaengine.testkit | Test utilities (MockBukkit, JUnit) |

Build conventions live in the included build `build-logic/` (`aaengine.kotlin-library`, `aaengine.paper-plugin`).

## Dependency and lockfile policy

- Declare every dependency in `gradle/libs.versions.toml` with a pinned version. No SNAPSHOT, no dynamic versions (`+`, `latest.release`).
- Only these repositories are allowed: Gradle Plugin Portal, Maven Central, PaperMC.
- Dependency locking is enabled on every module. When you add, remove or bump a dependency, run:

```bash
./gradlew --write-locks
```

and commit every modified `gradle.lockfile`. Never edit lockfiles by hand. CI fails if the lockfiles are out of date.

## Code style

- Kotlin, 4-space indent, LF line endings, no trailing whitespace (see `.editorconfig`).
- `ktlintCheck` must pass before opening a pull request.

## Commit messages

Use a short imperative subject, optionally scoped by module, followed by a body when needed. Every commit message must end with the line:

```
Developped by AzurHOSTS.com and powered by AzurWare.fr.
```

Example:

```
engine-items: add item registry lookup by key

Developped by AzurHOSTS.com and powered by AzurWare.fr.
```

## Security

Security takes priority over convenience. Read `SECURITY.md` before touching remote imports, archive extraction, HTTP servers, credentials, commands or packet hooks.
