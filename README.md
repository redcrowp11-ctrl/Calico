# Calico

NeoForge **1.21.1** mod — Phase 1 multi-biome world generation hooks.

Players will pick any subset of biomes (vanilla + modded) with per-biome weights; generation uses a deterministic weighted `BiomeSource` from the world seed + selection. Single biome ⇒ 100%.

## Requirements

- Java **21**
- NeoForge **21.1.x** / Minecraft **1.21.1**

## Build

```bash
./gradlew build
```

Artifact: `build/libs/calico-<version>.jar`

Compile only:

```bash
./gradlew compileJava
```

## Run client (dev)

```bash
./gradlew runClient
```

Other runs: `runServer`, `runData` (datagen for the Calico world preset).

## Phase 1 scope

| Included | Not included |
|----------|----------------|
| Mod entry + NeoForge 1.21.1 MDK layout | Full create-world UI polish |
| `calico:weighted` biome source | Fun tab behavior (stub only) |
| `calico:calico` world preset / world type | Preview map simulation |
| Locked config types + [CONFIG.md](CONFIG.md) | Push/auth (handled outside this scaffold) |
| Biome registry discovery (dimension-scoped) | |
| Fun package stub (no-op) | |

### Key packages

- `com.calico` — mod entry
- `com.calico.config` — locked schema + validation
- `com.calico.worldgen` — weighted source, preset hooks, discovery
- `com.calico.fun` — Phase 1 no-op stub
- `com.calico.client` — client bootstrap (UI later)

## Config

See **[CONFIG.md](CONFIG.md)** for the locked JSON schema (`version`, `selectedBiomes[{id,weight}]`).

## License

MIT (see project license / `mod_license` in `gradle.properties`).
