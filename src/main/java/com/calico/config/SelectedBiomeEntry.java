package com.calico.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * One entry in {@code selectedBiomes}. Field names are LOCKED for Phase 1 JSON:
 * {@code id}, {@code weight}.
 */
public record SelectedBiomeEntry(String id, double weight) {
    public static final Codec<SelectedBiomeEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(SelectedBiomeEntry::id),
            Codec.DOUBLE.fieldOf("weight").forGetter(SelectedBiomeEntry::weight)
    ).apply(instance, SelectedBiomeEntry::new));

    public ResourceLocation resourceLocationOrNull() {
        return ResourceLocation.tryParse(id);
    }
}
