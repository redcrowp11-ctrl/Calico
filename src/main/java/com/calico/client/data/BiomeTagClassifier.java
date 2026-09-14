package com.calico.client.data;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Climate / tag classification for UI filters and presets.
 * Temperature/humidity buckets derived from {@link Biome.ClimateSettings} (VERIFY NeoForge 1.21.1).
 */
@OnlyIn(Dist.CLIENT)
public final class BiomeTagClassifier {
    public enum TemperatureBucket {
        HOT,
        TEMPERATE,
        COLD,
        UNKNOWN
    }

    public enum HumidityBucket {
        DRY,
        NEUTRAL,
        HUMID,
        UNKNOWN
    }

    /** Default frequency when a biome is newly checked. Matches CONFIG.md examples. */
    public static final double DEFAULT_WEIGHT = 1.0d;

    private BiomeTagClassifier() {
    }

    public static TemperatureBucket temperature(Holder<Biome> holder) {
        try {
            float temp = holder.value().getBaseTemperature();
            if (temp < 0.15f) {
                return TemperatureBucket.COLD;
            }
            if (temp >= 1.0f) {
                return TemperatureBucket.HOT;
            }
            return TemperatureBucket.TEMPERATE;
        } catch (Exception ex) {
            return TemperatureBucket.UNKNOWN;
        }
    }

    public static HumidityBucket humidity(Holder<Biome> holder) {
        try {
            float downfall = holder.value().getModifiedClimateSettings().downfall();
            if (downfall < 0.2f) {
                return HumidityBucket.DRY;
            }
            if (downfall > 0.6f) {
                return HumidityBucket.HUMID;
            }
            return HumidityBucket.NEUTRAL;
        } catch (Exception ex) {
            return HumidityBucket.UNKNOWN;
        }
    }

    public static Boolean hasPrecipitation(Holder<Biome> holder) {
        try {
            return holder.value().hasPrecipitation();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Ocean / river / beach for No-oceans preset.
     * Uses {@link BiomeTags#IS_OCEAN}, {@link BiomeTags#IS_DEEP_OCEAN},
     * {@link BiomeTags#IS_RIVER}, {@link BiomeTags#IS_BEACH}.
     */
    public static boolean isOceanLike(Holder<Biome> holder) {
        return holder.is(BiomeTags.IS_OCEAN)
                || holder.is(BiomeTags.IS_DEEP_OCEAN)
                || holder.is(BiomeTags.IS_RIVER)
                || holder.is(BiomeTags.IS_BEACH);
    }

    /**
     * Desert-like matching for Deserts preset:
     * IS_BADLANDS, has_desert_pyramid tag, or id path contains {@code desert}.
     */
    public static boolean isDesertLike(Holder<Biome> holder, ResourceLocation id) {
        if (holder.is(BiomeTags.IS_BADLANDS) || holder.is(BiomeTags.HAS_DESERT_PYRAMID)) {
            return true;
        }
        String path = id.getPath();
        return path.contains("desert") || path.contains("badlands");
    }
}
