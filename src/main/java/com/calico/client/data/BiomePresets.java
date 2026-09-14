package com.calico.client.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.calico.worldgen.BiomeDimension;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Named selection presets for Phase 1.
 * <ul>
 *   <li><b>Deserts</b> — replace entire selection with desert-like biomes (see
 *       {@link BiomeTagClassifier#isDesertLike}).</li>
 *   <li><b>Cold</b> — replace entire selection with cold-temperature biomes.</li>
 *   <li><b>No oceans</b> — replace Overworld selection only: all Overworld biomes that are
 *       not ocean/river/beach; leave Nether/End selection untouched.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class BiomePresets {
    private BiomePresets() {
    }

    public static void applyDeserts(BiomeCatalog catalog, BiomeSelectionState state) {
        state.clear();
        for (BiomeEntry entry : catalog.all()) {
            if (entry.desertLike()) {
                state.selectWithDefault(entry);
            }
        }
    }

    public static void applyCold(BiomeCatalog catalog, BiomeSelectionState state) {
        state.clear();
        for (BiomeEntry entry : catalog.all()) {
            if (entry.temperatureBucket() == BiomeTagClassifier.TemperatureBucket.COLD) {
                state.selectWithDefault(entry);
            }
        }
    }

    public static void applyNoOceans(BiomeCatalog catalog, BiomeSelectionState state) {
        Set<ResourceLocation> toRemove = new HashSet<>();
        for (ResourceLocation id : state.selectedView().keySet()) {
            BiomeEntry entry = catalog.get(id);
            if (entry != null && entry.dimension() == BiomeDimension.OVERWORLD) {
                toRemove.add(id);
            }
        }
        state.removeAll(toRemove);

        List<BiomeEntry> overworldKeep = new ArrayList<>();
        for (BiomeEntry entry : catalog.all()) {
            if (entry.dimension() == BiomeDimension.OVERWORLD && !entry.oceanLike()) {
                overworldKeep.add(entry);
            }
        }
        state.putAllNew(overworldKeep);
    }
}
