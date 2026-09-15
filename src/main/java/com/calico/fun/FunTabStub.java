package com.calico.fun;

import com.calico.config.BiomeScale;

/**
 * Phase 1 Fun / options stub.
 * <p>
 * Biome scale ({@link BiomeScale}) is a first-class create-time option wired through
 * {@code CalicoWorldGenConfig.biomeScale} → {@code WeightedBiomeSource}. The create-world
 * screen exposes a CycleButton; DevBotAid (or a later Fun tab) can bind to the same field via
 * the constants below without renaming JSON keys.
 */
public final class FunTabStub {
    /** JSON / config field name for biome region scale (LOCKED additive key). */
    public static final String OPTION_BIOME_SCALE = "biomeScale";

    /** Default when unset — vanilla-comparable contiguous biomes. */
    public static final BiomeScale DEFAULT_BIOME_SCALE = BiomeScale.NORMAL;

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
}
