package com.calico.worldgen;

import java.util.function.Supplier;

import com.calico.Calico;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers Calico {@link BiomeSource} codecs into {@link Registries#BIOME_SOURCE}.
 */
public final class CalicoBiomeSources {
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, Calico.MOD_ID);

    public static final Supplier<MapCodec<WeightedBiomeSource>> WEIGHTED =
            BIOME_SOURCES.register("weighted", () -> WeightedBiomeSource.CODEC);

    private CalicoBiomeSources() {
    }

    public static void register(IEventBus modBus) {
        BIOME_SOURCES.register(modBus);
    }
}
