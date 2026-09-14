package com.calico.client.data;

import java.util.EnumSet;
import java.util.Set;

import com.calico.worldgen.BiomeDimension;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Active filters for the biome tile grid.
 * <p>
 * Combine rules (documented):
 * <ol>
 *   <li>Dimension tab (exact match)</li>
 *   <li>Vanilla/Modded (mutually exclusive with All)</li>
 *   <li>Tag chips: within each chip group (temp / humidity / precip), selected chips OR;
 *       empty group = no filter for that group. Groups AND together.</li>
 *   <li>Debounced search substring on display name or id</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class BiomeFilterState {
    public enum VanillaModdedFilter {
        ALL,
        VANILLA_ONLY,
        MODDED_ONLY
    }

    public enum PrecipFilter {
        HAS,
        HAS_NOT
    }

    private BiomeDimension dimensionTab = BiomeDimension.OVERWORLD;
    private String searchQuery = "";
    private VanillaModdedFilter vanillaModded = VanillaModdedFilter.ALL;
    private final Set<BiomeTagClassifier.TemperatureBucket> temperatures =
            EnumSet.noneOf(BiomeTagClassifier.TemperatureBucket.class);
    private final Set<BiomeTagClassifier.HumidityBucket> humidities =
            EnumSet.noneOf(BiomeTagClassifier.HumidityBucket.class);
    private final Set<PrecipFilter> precipitations = EnumSet.noneOf(PrecipFilter.class);

    public BiomeDimension dimensionTab() {
        return dimensionTab;
    }

    public void setDimensionTab(BiomeDimension dimensionTab) {
        this.dimensionTab = dimensionTab;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery == null ? "" : searchQuery;
    }

    public VanillaModdedFilter vanillaModded() {
        return vanillaModded;
    }

    public void setVanillaModded(VanillaModdedFilter vanillaModded) {
        this.vanillaModded = vanillaModded;
    }

    public Set<BiomeTagClassifier.TemperatureBucket> temperatures() {
        return temperatures;
    }

    public Set<BiomeTagClassifier.HumidityBucket> humidities() {
        return humidities;
    }

    public Set<PrecipFilter> precipitations() {
        return precipitations;
    }

    public void toggleTemperature(BiomeTagClassifier.TemperatureBucket bucket) {
        if (!temperatures.add(bucket)) {
            temperatures.remove(bucket);
        }
    }

    public void toggleHumidity(BiomeTagClassifier.HumidityBucket bucket) {
        if (!humidities.add(bucket)) {
            humidities.remove(bucket);
        }
    }

    public void togglePrecip(PrecipFilter filter) {
        if (!precipitations.add(filter)) {
            precipitations.remove(filter);
        }
    }

    public boolean matches(BiomeEntry entry) {
        if (entry.dimension() != dimensionTab) {
            return false;
        }
        switch (vanillaModded) {
            case VANILLA_ONLY -> {
                if (!entry.vanilla()) {
                    return false;
                }
            }
            case MODDED_ONLY -> {
                if (entry.vanilla()) {
                    return false;
                }
            }
            case ALL -> {
            }
        }
        if (!temperatures.isEmpty() && !temperatures.contains(entry.temperatureBucket())) {
            return false;
        }
        if (!humidities.isEmpty() && !humidities.contains(entry.humidityBucket())) {
            return false;
        }
        if (!precipitations.isEmpty()) {
            Boolean precip = entry.hasPrecipitation();
            if (precip == null) {
                return false;
            }
            boolean ok = false;
            if (precipitations.contains(PrecipFilter.HAS) && precip) {
                ok = true;
            }
            if (precipitations.contains(PrecipFilter.HAS_NOT) && !precip) {
                ok = true;
            }
            if (!ok) {
                return false;
            }
        }
        return entry.matchesSearch(searchQuery.toLowerCase());
    }
}
