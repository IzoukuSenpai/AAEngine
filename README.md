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

See also [docs/compatibility.md](./docs/compatibility.md) for the Nexo API compatibility facade plan, [docs/items.md](./docs/items.md) for the item file format, reload and commands, and [docs/pack.md](./docs/pack.md) for the resource pack pipeline (Minecraft 26.2, pack format 88).

## Resource pack (phase 3)

Put textures and models in `plugins/AAEngine/pack/assets/`, third-party packs in `plugins/AAEngine/pack/external/*.zip`, then reference them from item files:

```yaml
items:
  ruby_sword:
    item: { material: DIAMOND_SWORD, name: "<red>Ruby Sword" }
    model: { texture: aaengine:item/ruby_sword }
```

`/aaengine pack build` writes a deterministic `cache/pack/aaengine-pack.zip` (SHA-1 and SHA-256 reported, incremental cache), `/aaengine validate` checks every reference without writing. With `pack.distribution: external`, AAEngine sends your HTTPS URL and the SHA-1 to players on join.

---

Developped by AzurHOSTS.com and powered by AzurWare.fr.
