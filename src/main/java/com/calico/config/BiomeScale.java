package com.calico.config;

import com.mojang.serialization.Codec;

/**
 * Biome region scale for Calico weighted generation.
 * <ul>
 *   <li>{@link #NORMAL} — vanilla-comparable contiguous biome regions (default)</li>
 *   <li>{@link #QUILT} — high-frequency tight patchwork (few blocks wide)</li>
 * </ul>
 * JSON field: {@code "biomeScale": "normal" | "quilt"} (additive; omit → normal).
 */
public enum BiomeScale {
    NORMAL("normal"),
    QUILT("quilt");

    public static final Codec<BiomeScale> CODEC = Codec.STRING.xmap(BiomeScale::parseLenient, BiomeScale::serializedName);

    private final String serializedName;

    BiomeScale(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** True when this is the tight patchwork mode. */
    public boolean isQuilt() {
        return this == QUILT;
    }

    /**
     * Parses a JSON string; unknown / blank / null → {@link #NORMAL}.
     */
    public static BiomeScale parseLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String key = raw.trim().toLowerCase();
        for (BiomeScale scale : values()) {
            if (scale.serializedName.equals(key)) {
                return scale;
            }
        }
        // Aliases
        if ("tight".equals(key) || "patchwork".equals(key) || "micro".equals(key)) {
            return QUILT;
        }
        if ("vanilla".equals(key) || "large".equals(key) || "default".equals(key)) {
            return NORMAL;
        }
        return NORMAL;
    }
}
