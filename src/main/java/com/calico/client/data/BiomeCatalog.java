package com.calico.client.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.calico.worldgen.BiomeRegistryDiscovery;

import net.minecraft.core.HolderLookup;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Builds a client-side biome catalog once per screen open from the worldgen registry.
 */
@OnlyIn(Dist.CLIENT)
public final class BiomeCatalog {
    private final List<BiomeEntry> all;
    private final Map<ResourceLocation, BiomeEntry> byId;
    private final Set<ResourceLocation> presentIds;

    private BiomeCatalog(List<BiomeEntry> all) {
        this.all = List.copyOf(all);
        this.byId = new HashMap<>();
        this.presentIds = new HashSet<>();
        for (BiomeEntry entry : this.all) {
            byId.put(entry.id(), entry);
            presentIds.add(entry.id());
        }
    }

    public static BiomeCatalog build(HolderLookup.RegistryLookup<Biome> biomes) {
        List<BiomeEntry> entries = new ArrayList<>();
        for (BiomeRegistryDiscovery.DiscoveredBiome discovered : BiomeRegistryDiscovery.discoverAll(biomes)) {
            ResourceLocation id = discovered.id();
            String langKey = id.toLanguageKey("biome");
            Component name = Language.getInstance().has(langKey)
                    ? Component.translatable(langKey)
                    : Component.literal(id.toString());
            entries.add(new BiomeEntry(
                    id,
                    name,
                    id.getNamespace(),
                    discovered.dimension(),
                    BiomeTagClassifier.temperature(discovered.holder()),
                    BiomeTagClassifier.humidity(discovered.holder()),
                    BiomeTagClassifier.hasPrecipitation(discovered.holder()),
                    BiomeTagClassifier.isOceanLike(discovered.holder()),
                    BiomeTagClassifier.isDesertLike(discovered.holder(), id),
                    discovered.vanilla(),
                    BiomeTagClassifier.DEFAULT_WEIGHT,
                    discovered.holder()));
        }
        return new BiomeCatalog(entries);
    }

    public List<BiomeEntry> all() {
        return all;
    }

    public BiomeEntry get(ResourceLocation id) {
        return byId.get(id);
    }

    public Set<ResourceLocation> presentIds() {
        return presentIds;
    }

    public List<BiomeEntry> filter(BiomeFilterState filters) {
        List<BiomeEntry> out = new ArrayList<>();
        for (BiomeEntry entry : all) {
            if (filters.matches(entry)) {
                out.add(entry);
            }
        }
        return out;
    }
}
