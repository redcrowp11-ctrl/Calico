package com.calico.fun;

import com.calico.config.BiomeScale;
import com.calico.config.TerrainStyle;

/**
 * Phase 1 Fun / options stub.
 * <p>
 * Biome scale ({@link BiomeScale}) is a first-class create-time option wired through
 * {@code CalicoWorldGenConfig.biomeScale} → {@code WeightedBiomeSource}. Terrain style
 * ({@link TerrainStyle}) is an additive create-time config field (Phase 2) for later
 * generator wiring. The create-world screen exposes CycleButtons; DevBotAid (or a later
 * Fun tab) can bind to the same fields via the constants below without renaming JSON keys.
 */
public final class FunTabStub {
    /** JSON / config field name for biome region scale (LOCKED additive key). */
    public static final String OPTION_BIOME_SCALE = "biomeScale";

    /** JSON / config field name for terrain style (LOCKED additive key). */
    public static final String OPTION_TERRAIN_STYLE = "terrainStyle";

    /** Default when unset — vanilla-comparable contiguous biomes. */
    public static final BiomeScale DEFAULT_BIOME_SCALE = BiomeScale.NORMAL;

    /** Default when unset — normal overworld terrain. */
    public static final TerrainStyle DEFAULT_TERRAIN_STYLE = TerrainStyle.NORMAL;

    private FunTabStub() {
    }

    /** No-op placeholder so the Fun package exists without Fun-tab chrome. */
    public static void noop() {
        // intentionally empty
    }

    /**
     * DevBotAid hook: resolve a UI/config string to {@link BiomeScale}.
     * Unknown → {@link #DEFAULT_BIOME_SCALE}.
     */
    public static BiomeScale resolveBiomeScale(String raw) {
        return BiomeScale.parseLenient(raw);
    }

    /** DevBotAid hook: serialized name for persistence / CycleButton value. */
    public static String biomeScaleId(BiomeScale scale) {
        return scale == null ? DEFAULT_BIOME_SCALE.serializedName() : scale.serializedName();
    }

    /**
     * DevBotAid hook: resolve a UI/config string to {@link TerrainStyle}.
     * Unknown → {@link #DEFAULT_TERRAIN_STYLE}.
     */
    public static TerrainStyle resolveTerrainStyle(String raw) {
        return TerrainStyle.parseLenient(raw);
    }

    /** DevBotAid hook: serialized name for persistence / CycleButton value. */
    public static String terrainStyleId(TerrainStyle style) {
        return style == null ? DEFAULT_TERRAIN_STYLE.serializedName() : style.serializedName();
    }
}
