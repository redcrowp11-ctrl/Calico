package com.calico.config;

import com.mojang.serialization.Codec;

/**
 * Terrain generation style for Calico create-time config (Phase 2).
 * <p>
 * JSON field: {@code "terrainStyle"} (additive; omit → {@link #NORMAL}).
 * Resolved at create-time by {@code com.calico.worldgen.CalicoTerrainStyles} into
 * overworld {@code NoiseGeneratorSettings} (LevelStem / chunk gen).
 */
public enum TerrainStyle {
    NORMAL("normal"),
    SKY_ISLANDS("sky_islands"),
    ISLANDS("islands"),
    BIG_ISLANDS("big_islands"),
    MOUNTAINOUS("mountainous"),
    CAVE("cave"),
    WEDDING_CAKE("wedding_cake");

    public static final Codec<TerrainStyle> CODEC =
            Codec.STRING.xmap(TerrainStyle::parseLenient, TerrainStyle::serializedName);

    private final String serializedName;

    TerrainStyle(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /**
     * Parses a JSON string; unknown / blank / null → {@link #NORMAL}.
     */
    public static TerrainStyle parseLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String key = raw.trim().toLowerCase();
        for (TerrainStyle style : values()) {
            if (style.serializedName.equals(key)) {
                return style;
            }
        }
        // Aliases
        if ("default".equals(key) || "overworld".equals(key)) {
            return NORMAL;
        }
        if ("sky".equals(key)) {
            return SKY_ISLANDS;
        }
        if ("wedding".equals(key)) {
            return WEDDING_CAKE;
        }
        return NORMAL;
    }
}
