package com.calico.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>
 * Results are cached; call {@link #invalidateCache()} when registries/tags reload.
 */
public final class BiomeRegistryDiscovery {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bumped on registry/tag reload so hot paths stop using stale discovery. */
    private static final AtomicInteger CACHE_GENERATION = new AtomicInteger();

    private static volatile int cachedGeneration = -1;
    private static volatile HolderLookup.RegistryLookup<Biome> cachedLookup;
    private static volatile List<DiscoveredBiome> cachedAll;
    private static volatile Map<BiomeDimension, List<DiscoveredBiome>> cachedByDimension;

    public record DiscoveredBiome(
            ResourceLocation id,
            Holder<Biome> holder,
            BiomeDimension dimension,
            boolean vanilla
    ) {
    }

    private BiomeRegistryDiscovery() {
    }

    /** Invalidate cached discovery (e.g. after datapack / tag reload). */
    public static void invalidateCache() {
        CACHE_GENERATION.incrementAndGet();
        cachedLookup = null;
        cachedAll = null;
        cachedByDimension = null;
        Calico.LOGGER.debug("Calico discovery cache invalidated");
    }

    /**
     * Discovers all biomes visible in the given lookup (vanilla + modded).
     * Invalid / unkeyed holders are soft-dropped. Cached per lookup + generation.
     */
    public static List<DiscoveredBiome> discoverAll(HolderLookup.RegistryLookup<Biome> biomes) {
        List<DiscoveredBiome> hit = cachedAll;
        if (hit != null && cachedGeneration == CACHE_GENERATION.get() && cachedLookup == biomes) {
            return hit;
        }
        return rebuildCache(biomes).all;
    }

    /** Group discovered biomes by dimension scope. UNKNOWN is included when present. */
    public static Map<BiomeDimension, List<DiscoveredBiome>> byDimension(
            HolderLookup.RegistryLookup<Biome> biomes) {
        Map<BiomeDimension, List<DiscoveredBiome>> hit = cachedByDimension;
        if (hit != null && cachedGeneration == CACHE_GENERATION.get() && cachedLookup == biomes) {
            return hit;
        }
        return rebuildCache(biomes).byDimension;
    }

    public static List<DiscoveredBiome> forDimension(HolderLookup.RegistryLookup<Biome> biomes,
            BiomeDimension dimension) {
        return byDimension(biomes).getOrDefault(dimension, List.of());
    }

    private static CacheSnapshot rebuildCache(HolderLookup.RegistryLookup<Biome> biomes) {
        synchronized (BiomeRegistryDiscovery.class) {
            if (cachedAll != null && cachedGeneration == CACHE_GENERATION.get() && cachedLookup == biomes) {
                return new CacheSnapshot(cachedAll, cachedByDimension);
            }

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

            List<DiscoveredBiome> all = List.copyOf(out);
            Map<BiomeDimension, List<DiscoveredBiome>> map = new EnumMap<>(BiomeDimension.class);
            for (BiomeDimension dim : BiomeDimension.values()) {
                map.put(dim, new ArrayList<>());
            }
            for (DiscoveredBiome biome : all) {
                map.get(biome.dimension()).add(biome);
            }
            map.replaceAll((k, v) -> List.copyOf(v));
            Map<BiomeDimension, List<DiscoveredBiome>> byDim = Map.copyOf(map);

            cachedGeneration = CACHE_GENERATION.get();
            cachedLookup = biomes;
            cachedAll = all;
            cachedByDimension = byDim;
            return new CacheSnapshot(all, byDim);
        }
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

    /**
     * Returns true if {@code holder} belongs to {@code expected} dimension scope.
     * UNKNOWN expected accepts any classification.
     */
    public static boolean matchesDimension(Holder<Biome> holder, BiomeDimension expected) {
        if (expected == null || expected == BiomeDimension.UNKNOWN) {
            return true;
        }
        return classifyDimension(holder) == expected;
    }

    /** Convenience: resolve lookup from a full provider. */
    public static HolderLookup.RegistryLookup<Biome> biomeLookup(HolderLookup.Provider provider) {
        return provider.lookupOrThrow(Registries.BIOME);
    }

    private record CacheSnapshot(
            List<DiscoveredBiome> all,
            Map<BiomeDimension, List<DiscoveredBiome>> byDimension
    ) {
    }
}
