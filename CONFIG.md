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

| Serialized | Behavior (Phase 2 first slice) |
|------------|--------------------------------|
| `normal` | Default overworld noise settings (current Calico overworld) |
| `sky_islands` | Vanilla `floating_islands` noise settings + Calico biomes |
| `wedding_cake` | Thin stacked strata + large organic voids; sparse mega stalagmite columns; sealed bedrock floor (no void-kill) |
| `islands` | **Fallback → normal** (logged) |
| `big_islands` | **Fallback → normal** (logged) |
| `mountainous` | **Fallback → normal** (logged) |
| `cave` | **Fallback → normal** (logged) |

Aliases accepted leniently on parse: `default`/`overworld` → normal; `sky` → sky_islands; `wedding` → wedding_cake.

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
7. `terrainStyle` is baked into the overworld `NoiseBasedChunkGenerator` settings holder at create time.

Java types:

- `com.calico.config.CalicoWorldGenConfig` — `version` + `selectedBiomes` + `biomeScale` + `terrainStyle`
- `com.calico.config.SelectedBiomeEntry` — `id` + `weight`
- `com.calico.config.BiomeScale` — `NORMAL` / `QUILT`
- `com.calico.config.TerrainStyle` — `NORMAL` / `SKY_ISLANDS` / `ISLANDS` / `BIG_ISLANDS` / `MOUNTAINOUS` / `CAVE` / `WEDDING_CAKE`
- `com.calico.worldgen.CalicoTerrainStyles` — style → `NoiseGeneratorSettings` resolver
- Mojang `Codec`s on config types for JSON (de)serialization

Do **not** rename locked JSON keys (`version`, `selectedBiomes`, `id`, `weight`) in Phase 1.
`biomeScale` and `terrainStyle` are additive; existing configs without them default to normal.
