package com.calico.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.calico.Calico;
import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

/**
 * Registry discovery helpers for vanilla + modded biomes, scoped by dimension.
 * Soft-drops entries that lack a registry key or fail climate/tag reads, with a clear log note.
 */
public final class BiomeRegistryDiscovery {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record DiscoveredBiome(
            ResourceLocation id,
            Holder<Biome> holder,
            BiomeDimension dimension,
            boolean vanilla
    ) {
    }

    private BiomeRegistryDiscovery() {
    }

    /**
     * Discovers all biomes visible in the given lookup (vanilla + modded).
     * Invalid / unkeyed holders are soft-dropped.
     */
    public static List<DiscoveredBiome> discoverAll(HolderLookup.RegistryLookup<Biome> biomes) {
        List<DiscoveredBiome> out = new ArrayList<>();
        int dropped = 0;
        for (Holder.Reference<Biome> holder : biomes.listElements().toList()) {
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isEmpty()) {
                dropped++;
                LOGGER.warn("Calico discovery: soft-dropping biome holder without registry key");
                continue;
            }
            ResourceLocation id = key.get().location();
            BiomeDimension dimension;
            try {
                dimension = classifyDimension(holder);
            } catch (Exception ex) {
                dropped++;
                LOGGER.warn("Calico discovery: soft-dropping '{}' due to climate/tag read failure: {}",
                        id, ex.toString());
                continue;
            }
            boolean vanilla = "minecraft".equals(id.getNamespace());
            out.add(new DiscoveredBiome(id, holder, dimension, vanilla));
        }
        out.sort(Comparator.comparing(d -> d.id().toString()));
        if (dropped > 0) {
            LOGGER.info("Calico discovery: soft-dropped {} invalid/missing climate/registry biome entries",
                    dropped);
        }
        Calico.LOGGER.debug("Calico discovery: {} biomes (vanilla+modded)", out.size());
        return List.copyOf(out);
    }

    /** Group discovered biomes by dimension scope. UNKNOWN is included when present. */
    public static Map<BiomeDimension, List<DiscoveredBiome>> byDimension(
            HolderLookup.RegistryLookup<Biome> biomes) {
        Map<BiomeDimension, List<DiscoveredBiome>> map = new EnumMap<>(BiomeDimension.class);
        for (BiomeDimension dim : BiomeDimension.values()) {
            map.put(dim, new ArrayList<>());
        }
        for (DiscoveredBiome biome : discoverAll(biomes)) {
            map.get(biome.dimension()).add(biome);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(map);
    }

    public static List<DiscoveredBiome> forDimension(HolderLookup.RegistryLookup<Biome> biomes,
            BiomeDimension dimension) {
        return discoverAll(biomes).stream().filter(b -> b.dimension() == dimension).toList();
    }

    /**
     * Classifies a biome into Overworld / Nether / End using biome tags.
     * Prefer IS_NETHER / IS_END; otherwise treat as OVERWORLD when IS_OVERWORLD or untagged.
     */
    public static BiomeDimension classifyDimension(Holder<Biome> holder) {
        if (holder.is(BiomeTags.IS_NETHER)) {
            return BiomeDimension.NETHER;
        }
        if (holder.is(BiomeTags.IS_END)) {
            return BiomeDimension.END;
        }
        // IS_OVERWORLD exists on 1.21.x; if absent at compile time this still compiles via the tag key.
        if (holder.is(BiomeTags.IS_OVERWORLD)) {
            return BiomeDimension.OVERWORLD;
        }
        // Fallback: many modded overworld biomes omit IS_OVERWORLD — treat as overworld unless nether/end.
        return BiomeDimension.OVERWORLD;
    }

    /** Convenience: resolve lookup from a full provider. */
    public static HolderLookup.RegistryLookup<Biome> biomeLookup(HolderLookup.Provider provider) {
        return provider.lookupOrThrow(Registries.BIOME);
    }
}
