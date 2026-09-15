package com.calico.worldgen;

import java.util.List;

import com.calico.Calico;
import com.calico.config.TerrainStyle;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Resolves {@link TerrainStyle} → overworld {@link NoiseGeneratorSettings} for create-time LevelStem baking.
 * <ul>
 *   <li>{@link TerrainStyle#NORMAL} — vanilla overworld</li>
 *   <li>{@link TerrainStyle#SKY_ISLANDS} — vanilla floating_islands</li>
 *   <li>{@link TerrainStyle#WEDDING_CAKE} — stacked strata, organic voids, sparse column connectors</li>
 *   <li>Other styles — safe fallback to normal with an info log</li>
 * </ul>
 */
public final class CalicoTerrainStyles {
    private CalicoTerrainStyles() {
    }

    /**
     * Picks noise settings for the overworld stem from {@code style}.
     * Always returns a usable holder (falls back to overworld on unknown / unimplemented).
     */
    public static Holder<NoiseGeneratorSettings> resolveOverworldSettings(
            TerrainStyle style,
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            HolderGetter<NormalNoise.NoiseParameters> noises) {
        TerrainStyle resolved = style == null ? TerrainStyle.NORMAL : style;
        return switch (resolved) {
            case NORMAL -> noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            case SKY_ISLANDS -> {
                Calico.LOGGER.info("Calico: terrainStyle=sky_islands → floating_islands noise settings");
                yield noiseSettings.getOrThrow(NoiseGeneratorSettings.FLOATING_ISLANDS);
            }
            case WEDDING_CAKE -> {
                Calico.LOGGER.info("Calico: terrainStyle=wedding_cake → stacked-strata density");
                yield Holder.direct(buildWeddingCakeSettings(noises));
            }
            case ISLANDS, BIG_ISLANDS, MOUNTAINOUS, CAVE -> {
                Calico.LOGGER.info(
                        "Calico: terrainStyle={} not yet implemented — falling back to normal overworld",
                        resolved.serializedName());
                yield noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            }
        };
    }

    /**
     * Wedding-cake: several horizontal strata with noise-warped surfaces (organic continuous voids,
     * not laser-flat), plus sparse deterministic pillar columns bridging the gaps.
     */
    static NoiseGeneratorSettings buildWeddingCakeSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction finalDensity = postProcess(weddingCakeDensity(noises));
        // initialDensity ≈ coarse solid occupancy for spawn / aquifers off
        DensityFunction initial = DensityFunctions.max(
                weddingCakeLayerBands(DensityFunctions.zero()),
                DensityFunctions.constant(-0.5));

        NoiseRouter router = new NoiseRouter(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                initial,
                finalDensity,
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero());

        return new NoiseGeneratorSettings(
                NoiseSettings.create(-64, 384, 1, 2),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                router,
                SurfaceRuleData.overworldLike(false, false, true),
                List.of(),
                -64, // no ocean fill — voids stay open
                false,
                false,
                false,
                false);
    }

    private static DensityFunction weddingCakeDensity(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Horizontal warp so stratum surfaces undulate (organic voids, not laser-flat slabs).
        DensityFunction surfaceWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.75, 0.0),
                DensityFunctions.constant(0.45));
        DensityFunction cheeseWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.4, 0.4),
                DensityFunctions.constant(0.18));
        DensityFunction warp = DensityFunctions.add(surfaceWarp, cheeseWarp);

        DensityFunction layers = weddingCakeLayerBands(warp);
        DensityFunction columns = sparseColumns(noises);
        return DensityFunctions.max(layers, columns);
    }

    /**
     * Four stacked strata (thicker at the bottom). Each band is min(floorRise, ceilingFall) + warp.
     */
    private static DensityFunction weddingCakeLayerBands(DensityFunction warp) {
        // centerY, halfThickness, edgeSoftness
        DensityFunction base = warpedBand(8, 44, 14, warp);      // thick foundation ~ Y -50..66
        DensityFunction mid1 = warpedBand(105, 20, 12, warp);    // ~ Y 73..137
        DensityFunction mid2 = warpedBand(175, 14, 10, warp);    // ~ Y 151..199
        DensityFunction top = warpedBand(235, 10, 8, warp);      // ~ Y 217..253
        return DensityFunctions.max(
                DensityFunctions.max(base, mid1),
                DensityFunctions.max(mid2, top));
    }

    private static DensityFunction warpedBand(int centerY, int halfThickness, int edgeSoftness, DensityFunction warp) {
        int floorStart = centerY - halfThickness - edgeSoftness;
        int floorEnd = centerY - halfThickness;
        int ceilStart = centerY + halfThickness;
        int ceilEnd = centerY + halfThickness + edgeSoftness;
        DensityFunction floor = DensityFunctions.yClampedGradient(floorStart, floorEnd, -1.0, 1.0);
        DensityFunction ceiling = DensityFunctions.yClampedGradient(ceilStart, ceilEnd, 1.0, -1.0);
        DensityFunction band = DensityFunctions.min(floor, ceiling);
        return DensityFunctions.add(band, warp);
    }

    /**
     * Sparse, deterministic pillar / stalagmite-stalactite columns (vanilla pillar noises, rarer).
     */
    private static DensityFunction sparseColumns(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction pillar = DensityFunctions.noise(noises.getOrThrow(Noises.PILLAR), 22.0, 0.28);
        // More negative rarity → fewer columns (sparse).
        DensityFunction rarity = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -3.2);
        DensityFunction thickness = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_THICKNESS), 0.0, 1.2);
        DensityFunction shaped = DensityFunctions.add(
                DensityFunctions.mul(pillar, DensityFunctions.constant(2.0)),
                rarity);
        return DensityFunctions.cacheOnce(DensityFunctions.mul(shaped, thickness.cube()));
    }

    /** Same squeeze pipeline vanilla uses for final_density readability. */
    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        return DensityFunctions.mul(DensityFunctions.interpolated(blended), DensityFunctions.constant(0.64)).squeeze();
    }
}
