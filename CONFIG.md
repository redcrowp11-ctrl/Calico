# Calico config schema (Phase 1 — LOCKED field names)

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
| `terrainStyle` | string | Terrain generator style (config field only in Phase 2). Omitted / unknown → **`normal`**. Do not rename; additive only. |

### `biomeScale` behavior

- **`normal`** (default) — vanilla-comparable contiguous biome regions (large Voronoi cells ≈ 512 blocks). Still only uses selected biomes + weights.
- **`quilt`** — high-frequency tight patchwork (per-quart hash); patches a few blocks wide. The original Calico look, kept as a toggle.

Aliases accepted leniently on parse: `vanilla`/`large`/`default` → normal; `tight`/`patchwork`/`micro` → quilt.

### `terrainStyle` values

| Serialized | Meaning |
|------------|---------|
| `normal` | Current overworld terrain (default when omitted) |
| `sky_islands` | Floating sky landmasses |
| `islands` | Scattered ocean islands |
| `big_islands` | Large continent-scale islands |
| `mountainous` | Rugged peaks and valleys |
| `cave` | Vast underground cave worlds |
| `wedding_cake` | Stacked strata with organic continuous voids and occasional column connectors |

Aliases accepted leniently on parse: `default`/`overworld` → normal; `sky` → sky_islands; `wedding` → wedding_cake.

Phase 2 ships the config field + Customize CycleButton only; terrain generators are wired later (DevBot).

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
4. Calico world-type Customize opens `CalicoCreateWorldScreen`; selecting Calico with a pending config also auto-bakes into LevelStem on the create-world screen.
5. `biomeScale` is baked into `WeightedBiomeSource` (codec field `biomeScale`) so Customize / reload preserves Normal vs Quilt.
6. `terrainStyle` is persisted on `CalicoWorldGenConfig` for later terrain-generator wiring; it is not yet consumed by noise/chunk generators.

Java types:

- `com.calico.config.CalicoWorldGenConfig` — `version` + `selectedBiomes` + `biomeScale` + `terrainStyle`
- `com.calico.config.SelectedBiomeEntry` — `id` + `weight`
- `com.calico.config.BiomeScale` — `NORMAL` / `QUILT`
- `com.calico.config.TerrainStyle` — `NORMAL` / `SKY_ISLANDS` / `ISLANDS` / `BIG_ISLANDS` / `MOUNTAINOUS` / `CAVE` / `WEDDING_CAKE`
- Mojang `Codec`s on config types for JSON (de)serialization

Do **not** rename locked JSON keys (`version`, `selectedBiomes`, `id`, `weight`) in Phase 1.
`biomeScale` and `terrainStyle` are additive; existing configs without them default to normal.
