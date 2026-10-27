# AAEngine

AAEngine is a clean-room Paper/Folia custom-content engine specification and implementation workspace.

Current scope:

- custom items
- custom blocks
- furniture
- mechanics
- recipes
- glyphs/fonts/sounds
- resource-pack compilation and publishing
- version bridges
- integrations

Start with [`AAENGINE_RECONSTRUCTION.md`](./AAENGINE_RECONSTRUCTION.md).

## Clean-room rule

This repository is intended for an independent implementation based on documented behavior, public APIs, runtime observations, and clean-room specifications. Do not commit copied or decompiled proprietary source code.

## Build

Requirements: JDK 25 (Temurin). Gradle is provided by the wrapper.

```bash
./gradlew build
```

The Paper plugin jar is produced in `engine-paper/build/libs/`.

Modules:

- engine-api
- engine-core
- engine-paper
- engine-items
- engine-mechanics
- engine-blocks
- engine-furniture
- engine-recipes
- engine-pack
- engine-fonts
- engine-version-common
- engine-integrations
- engine-testkit

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the module layout, lockfile policy and commit conventions.

See also [docs/compatibility.md](./docs/compatibility.md) for the Nexo API compatibility facade plan.

See [docs/itemsadder-compat.md](./docs/itemsadder-compat.md) for the ItemsAdder compatibility and migration design (French).

