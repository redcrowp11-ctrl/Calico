package com.calico.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Codec entry for {@link WeightedBiomeSource}: biome holder + positive weight.
 */
public record WeightedBiomeEntry(Holder<Biome> biome, double weight) {
    public static final Codec<WeightedBiomeEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Biome.CODEC.fieldOf("biome").forGetter(WeightedBiomeEntry::biome),
            Codec.DOUBLE.fieldOf("weight").forGetter(WeightedBiomeEntry::weight)
    ).apply(instance, WeightedBiomeEntry::new));
}
