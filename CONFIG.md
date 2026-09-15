# Calico config schema (Phase 1 — LOCKED field names + Phase 2 terrain)

Canonical JSON for biome selection (export/import, last-selection, world-create handoff):

```json
{
  "version": 1,
  "selectedBiomes": [
    { "id": "minecraft:desert", "weight": 1.0 }
  ],
  "biomeScale": "normal",
  "terrainStyle": "normal"
}
```

## Locked fields

| Field | Type | Rules |
|-------|------|--------|
| `version` | int | Phase 1 = `1` |
| `selectedBiomes` | array | Ordered list; duplicate `id` → **last wins** after sanitize |
| `selectedBiomes[].id` | string | Full resource location `modid:biome` |
| `selectedBiomes[].weight` | number | Must be **finite and > 0**. Non-positive, **NaN**, and **Inf** weights are **ignored** (dropped) with a warning |

## Additive optional fields

| Field | Type | Rules |
|-------|------|--------|
| `biomeScale` | string | `"normal"` (default) or `"quilt"`. Omitted / unknown → **`normal`**. Do not rename; additive only. |
| `terrainStyle` | string | Terrain generator style. Omitted / unknown → **`normal`**. Do not rename; additive only. **LOCKED** name. |

### `biomeScale` behavior

- **`normal`** (default) — vanilla-comparable contiguous biome regions (large Voronoi cells ≈ 512 blocks) with a light ~24-block border dither. Still only uses selected biomes + weights.
- **`quilt`** — high-frequency tight patchwork (per-quart hash); patches a few blocks wide. The original Calico look, kept as a toggle.

Aliases accepted leniently on parse: `vanilla`/`large`/`default` → normal; `tight`/`patchwork`/`micro` → quilt.

### `terrainStyle` values

| Serialized | Behavior |
|------------|----------|
| `normal` | Default overworld noise settings (current Calico overworld) |
| `sky_islands` | End-like floating islands over void (`end_islands` + 3D cheese); registered as `calico:sky_islands` |
| `islands` | Regular sea-level islands in ocean; registered as `calico:islands` |
| `big_islands` | Larger sea-level islands with wider ocean gaps; registered as `calico:big_islands` |
| `mountainous` | Tall mountains via vanilla amplified (`minecraft:amplified`) |
| `cave` | Cave-focused world via vanilla caves (`minecraft:caves`) |
| `wedding_cake` | Layered strata + organic voids + thick mega columns; sealed bedrock floor; registered as `calico:wedding_cake` |
| `ant_hill` | Smooth tall mountains with dense interconnect tunnels; registered as `calico:ant_hill` |

Aliases accepted leniently on parse: `standard`/`default`/`overworld` → normal; `sky` → sky_islands; `wedding` → wedding_cake; `anthill`/`ant-hill` → ant_hill; `mountains`/`amplified` → mountainous; `caves` → cave. Unknown values log ERROR and fall back to normal.

Default terrain when omitted = **normal** (current overworld). Terrain styles layer on top of `selectedBiomes`+weights and `biomeScale` (WeightedBiomeSource unchanged).

## Validation (create gate)

Shared API: `com.calico.config.CalicoConfigValidation#validateForCreate`

- Empty `selectedBiomes` **OR** all weights ≤ 0 / non-finite → **invalid** (block create).
- UI message: *"Select at least one biome with a positive weight to create a world."*
- Invalid / unparsable ids are skipped with warnings.
- Missing registry biomes and **wrong-dimension** biomes are soft-dropped at generation resolve time (see `WeightedBiomeSource.fromConfig` / `resolveEntries`) with a clear log note.
- `biomeScale` and `terrainStyle` do not affect create gating.

## Create-time → LevelStem

1. UI / handoff calls `CalicoCreateWorldBridge.submit(config, seed)` (or `apply(...)`).
2. Pending config is held in `CalicoCreateTimeConfig`.
3. `CalicoWorldPresets.applyCreateTimeConfig` rebuilds Overworld (required) and optionally Nether/End LevelStem biome sources from the selection, scoped by `BiomeDimension`.
4. Overworld chunk gen uses `CalicoTerrainStyles.resolveOverworldSettings(terrainStyle, …)` so noise settings match the picker (WeightedBiomeSource + biomeScale preserved).
5. Calico world-type Customize opens `CalicoCreateWorldScreen` (CycleButtons for biome scale + terrain style); selecting Calico with a pending config also auto-bakes into LevelStem on the create-world screen.
6. `biomeScale` is baked into `WeightedBiomeSource` (codec field `biomeScale`) so Customize / reload preserves Normal vs Quilt.
7. `terrainStyle` is baked into the overworld `NoiseBasedChunkGenerator` settings holder at create time via registry keys (`calico:sky_islands`, `calico:wedding_cake`, `calico:islands`, `calico:big_islands`, `calico:ant_hill`, or vanilla `minecraft:amplified` / `minecraft:caves`).
8. Create-world auto-bake re-applies whenever the overworld stem is still vanilla OVERWORLD while a custom terrainStyle is pending (world-type reset used to skip re-bake). Re-bake also runs on screen init and every client tick while Create World is open on Calico. Quiet INFO logs confirm bake; no banner/toast spam.

Java types:

- `com.calico.config.CalicoWorldGenConfig` — `version` + `selectedBiomes` + `biomeScale` + `terrainStyle`
- `com.calico.config.SelectedBiomeEntry` — `id` + `weight`
- `com.calico.config.BiomeScale` — `NORMAL` / `QUILT`
- `com.calico.config.TerrainStyle` — `NORMAL` / `SKY_ISLANDS` / `ISLANDS` / `BIG_ISLANDS` / `MOUNTAINOUS` / `CAVE` / `WEDDING_CAKE` / `ANT_HILL`
- `com.calico.worldgen.CalicoTerrainStyles` — style → `NoiseGeneratorSettings` resolver
- Mojang `Codec`s on config types for JSON (de)serialization

## Export / import (style presets)

Clipboard **Export** / **Import** (Filters → Data) and file round-trip helpers serialize the **full** create config via `CalicoWorldGenConfig.CODEC`:

- `version`, `selectedBiomes[{id,weight}]`, `biomeScale`, `terrainStyle`

Same schema as last-selection persistence (`config/calico-last-selection.json`).

Java helpers:

- `com.calico.client.screen.ExportImportHelper` — clipboard + `writeToFile` / `readFromFile`
- `com.calico.client.data.BiomeSelectionPersistence` — last-selection file under the game dir

Omitted `biomeScale` / `terrainStyle` on import default to `normal` (additive, backward compatible).

## Determinism stamp

On create-time submit / LevelStem bake, Calico logs a short stamp for bug reports:

```
Calico: determinism stamp seed=<worldSeed> config=<16-hex> (style=…, scale=…, biomes=N)
```

`config` is a stable SHA-256 fingerprint of sanitized biomes + weights + `biomeScale` + `terrainStyle` (`com.calico.config.CalicoConfigFingerprint`). Same inputs ⇒ same fingerprint.

## Safe spawn (dangerous terrain)

For Calico overworld stems with **custom** `terrainStyle` (anything ≠ `normal` / non-`OVERWORLD` noise — including `sky_islands`, `wedding_cake`, `ant_hill`, `islands`, `big_islands`, `cave`, `mountainous`):

1. On world create (`LevelEvent.CreateSpawnPosition`), search near noise-sampler spawn for a solid top surface with air above (deterministic from world seed).
2. Place a small platform only if no natural spot is found within the search spiral.
3. On first join, repair unsafe landings (void / air / lava) and teleport once.

**Normal** overworld terrain is unchanged (vanilla spawn logic).

Do **not** rename locked JSON keys (`version`, `selectedBiomes`, `id`, `weight`) in Phase 1.
`biomeScale` and `terrainStyle` are additive; existing configs without them default to normal.
