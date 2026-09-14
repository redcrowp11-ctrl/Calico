# Calico config schema (Phase 1 — LOCKED field names)

Canonical JSON for biome selection (export/import, last-selection, world-create handoff):

```json
{
  "version": 1,
  "selectedBiomes": [
    { "id": "minecraft:desert", "weight": 1.0 }
  ]
}
```

## Locked fields

| Field | Type | Rules |
|-------|------|--------|
| `version` | int | Phase 1 = `1` |
| `selectedBiomes` | array | Ordered list; duplicate `id` → **last wins** after sanitize |
| `selectedBiomes[].id` | string | Full resource location `modid:biome` |
| `selectedBiomes[].weight` | number | Must be **finite and > 0**. Non-positive, **NaN**, and **Inf** weights are **ignored** (dropped) with a warning |

## Validation (create gate)

Shared API: `com.calico.config.CalicoConfigValidation#validateForCreate`

- Empty `selectedBiomes` **OR** all weights ≤ 0 / non-finite → **invalid** (block create).
- UI message: *"Select at least one biome with a positive weight to create a world."*
- Invalid / unparsable ids are skipped with warnings.
- Missing registry biomes and **wrong-dimension** biomes are soft-dropped at generation resolve time (see `WeightedBiomeSource.fromConfig` / `resolveEntries`) with a clear log note.

## Create-time → LevelStem

1. UI / handoff calls `CalicoCreateWorldBridge.submit(config, seed)` (or `apply(...)`).
2. Pending config is held in `CalicoCreateTimeConfig`.
3. `CalicoWorldPresets.applyCreateTimeConfig` rebuilds Overworld (required) and optionally Nether/End LevelStem biome sources from the selection, scoped by `BiomeDimension`.
4. Calico world-type Customize opens `CalicoCreateWorldScreen`; selecting Calico with a pending config also auto-bakes into LevelStem on the create-world screen.

Java types:

- `com.calico.config.CalicoWorldGenConfig` — `version` + `selectedBiomes`
- `com.calico.config.SelectedBiomeEntry` — `id` + `weight`
- Mojang `Codec`s on both for JSON (de)serialization

Do **not** rename these JSON keys in Phase 1.
