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
 *   <li>{@link TerrainStyle#WEDDING_CAKE} — stacked strata, organic voids, sparse mega-column connectors</li>
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
     * Wedding-cake: several thin horizontal strata with strong noise-warped surfaces (organic continuous
     * voids, not laser-flat), sparse mega stalagmite/stalactite columns bridging the gaps, and a sealed
     * bedrock floor so players cannot fall into the world-void kill.
     */
    static NoiseGeneratorSettings buildWeddingCakeSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction finalDensity = postProcess(weddingCakeDensity(noises));
        // initialDensity ≈ coarse solid occupancy for spawn / aquifers off
        DensityFunction initial = DensityFunctions.max(
                DensityFunctions.max(weddingCakeLayerBands(DensityFunctions.zero()), sealedWorldFloor()),
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
        // Stronger horizontal warp so stratum floors/ceilings undulate (organic, not slab-flat).
        DensityFunction surfaceWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.55, 0.0),
                DensityFunctions.constant(0.95));
        DensityFunction cheeseWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.35, 0.35),
                DensityFunctions.constant(0.40));
        DensityFunction jaggedWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.5, 0.0),
                DensityFunctions.constant(0.22));
        DensityFunction warp = DensityFunctions.add(surfaceWarp, DensityFunctions.add(cheeseWarp, jaggedWarp));

        DensityFunction layers = weddingCakeLayerBands(warp);
        DensityFunction columns = sparseMegaColumns(noises);
        // Floor seal is unwarped — warp must never punch a kill-hole through bedrock.
        return DensityFunctions.max(DensityFunctions.max(layers, columns), sealedWorldFloor());
    }

    /**
     * Hard solid apron from minY upward. Unwarped so the bottom can never open into void-kill.
     * Fades out around Y -44 so it meets the lowest cake plate without a hard shelf.
     */
    private static DensityFunction sealedWorldFloor() {
        return DensityFunctions.yClampedGradient(-52, -44, 1.0, -1.0);
    }

    /**
     * Six thin stacked plates with large voids between them (more air / thinner solids than v1).
     * Each band is min(floorRise, ceilingFall) + warp.
     */
    private static DensityFunction weddingCakeLayerBands(DensityFunction warp) {
        // centerY, halfThickness, edgeSoftness — thinner plates, wider gaps (~25–40 block voids)
        DensityFunction l1 = warpedBand(12, 9, 11, warp);   // ~ Y -8..32
        DensityFunction l2 = warpedBand(68, 7, 10, warp);   // ~ Y 51..85
        DensityFunction l3 = warpedBand(118, 6, 9, warp);   // ~ Y 103..133
        DensityFunction l4 = warpedBand(162, 6, 8, warp);   // ~ Y 148..176
        DensityFunction l5 = warpedBand(208, 5, 8, warp);   // ~ Y 195..221
        DensityFunction l6 = warpedBand(250, 5, 7, warp);   // ~ Y 238..262
        return DensityFunctions.max(
                DensityFunctions.max(DensityFunctions.max(l1, l2), DensityFunctions.max(l3, l4)),
                DensityFunctions.max(l5, l6));
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
     * Sparse, deterministic mega stalagmite/stalactite columns (vanilla dripstone-mega vibe:
     * wide bases, vertical continuity — not thin pencil pillars).
     */
    private static DensityFunction sparseMegaColumns(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Lower xz scale → wider features; lower y scale → taller vertical continuity.
        DensityFunction pillar = DensityFunctions.noise(noises.getOrThrow(Noises.PILLAR), 9.0, 0.12);
        // Still sparse (more negative rarity → fewer columns).
        DensityFunction rarity = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -2.9);
        // Thicker body (vanilla dripstone mega feel).
        DensityFunction thickness = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_THICKNESS), 0.45, 2.0);
        DensityFunction shaped = DensityFunctions.add(
                DensityFunctions.mul(pillar, DensityFunctions.constant(2.6)),
                rarity);
        return DensityFunctions.cacheOnce(DensityFunctions.mul(shaped, thickness.cube()));
    }

    /** Same squeeze pipeline vanilla uses for final_density readability. */
    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        return DensityFunctions.mul(DensityFunctions.interpolated(blended), DensityFunctions.constant(0.64)).squeeze();
    }
}
