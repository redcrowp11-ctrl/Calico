package com.calico.client.data;

import com.calico.worldgen.BiomeDimension;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Immutable catalog item for one biome in the create-world UI.
 */
@OnlyIn(Dist.CLIENT)
public record BiomeEntry(
        ResourceLocation id,
        Component displayName,
        String namespace,
        BiomeDimension dimension,
        BiomeTagClassifier.TemperatureBucket temperatureBucket,
        BiomeTagClassifier.HumidityBucket humidityBucket,
        Boolean hasPrecipitation,
        boolean oceanLike,
        boolean desertLike,
        boolean vanilla,
        double defaultWeight,
        Holder<Biome> holder
) {
    public boolean matchesSearch(String queryLower) {
        if (queryLower == null || queryLower.isBlank()) {
            return true;
        }
        return displayName.getString().toLowerCase().contains(queryLower)
                || id.toString().toLowerCase().contains(queryLower);
    }
}
