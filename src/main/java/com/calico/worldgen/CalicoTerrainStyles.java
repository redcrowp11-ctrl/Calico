package com.calico.worldgen;

import java.util.List;

import com.calico.Calico;
import com.calico.config.TerrainStyle;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Resolves {@link TerrainStyle} → overworld {@link NoiseGeneratorSettings} for create-time LevelStem baking.
 * <ul>
 *   <li>{@link TerrainStyle#NORMAL} — vanilla overworld</li>
 *   <li>{@link TerrainStyle#SKY_ISLANDS} — End-like discrete floating islands (not vanilla floating_islands cheese)</li>
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
                Calico.LOGGER.info("Calico: terrainStyle=sky_islands → end-like floating island density");
                yield Holder.direct(buildSkyIslandsSettings());
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
     * Sky islands: discrete End-like floating landmasses with overworld surface rules.
     * <p>
     * Vanilla {@code floating_islands} only slides End {@code base_3d_noise} — continuous cheese that
     * reads as mountain ceilings + thin pillars. Proper islands need {@code end_islands} (2D selector)
     * plus that 3D cheese, matching {@code NoiseRouterData.end}.
     */
    static NoiseGeneratorSettings buildSkyIslandsSettings() {
        // Same old_blended_noise params as minecraft:end/base_3d_noise
        DensityFunction base3d = BlendedNoise.createUnseeded(0.25, 0.25, 80.0, 160.0, 4.0);
        DensityFunction islands = DensityFunctions.cache2d(DensityFunctions.endIslands(0L));
        DensityFunction slopedCheese = DensityFunctions.add(DensityFunctions.endIslands(0L), base3d);

        // Slide window matches vanilla floating_islands height (0..256), End-like falloff constants.
        DensityFunction finalDensity = postProcess(slideEndLike(slopedCheese, 0, 256));
        DensityFunction initial = slideEndLike(
                DensityFunctions.add(islands, DensityFunctions.constant(-0.703125)), 0, 256);

        NoiseRouter router = new NoiseRouter(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                islands, // erosion slot mirrors End (unused by WeightedBiomeSource)
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                initial,
                finalDensity,
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero());

        return new NoiseGeneratorSettings(
                NoiseSettings.create(0, 256, 2, 1), // End/floating cell size — chunky island silhouettes
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                router,
                // Overworld biome surfaces on islands; no bedrock floor/roof — open void
                net.minecraft.data.worldgen.SurfaceRuleData.overworldLike(false, false, false),
                List.of(),
                -64, // no ocean fill
                false,
                false,
                false,
                true); // legacy random — End island + blended noise wiring
    }

    /**
     * Same vertical slide vanilla uses for End / floating_islands
     * ({@code NoiseRouterData.slideEndLike}).
     */
    private static DensityFunction slideEndLike(DensityFunction density, int minY, int height) {
        return slide(density, minY, height, 72, -184, -23.4375, 4, 32, -0.234375);
    }

    private static DensityFunction slide(
            DensityFunction input,
            int minY,
            int height,
            int topStartOffset,
            int topEndOffset,
            double topTarget,
            int bottomStartOffset,
            int bottomEndOffset,
            double bottomTarget) {
        DensityFunction topSlide = DensityFunctions.yClampedGradient(
                minY + height - topStartOffset, minY + height - topEndOffset, 1.0, 0.0);
        DensityFunction afterTop = DensityFunctions.lerp(topSlide, topTarget, input);
        DensityFunction bottomSlide = DensityFunctions.yClampedGradient(
                minY + bottomStartOffset, minY + bottomEndOffset, 0.0, 1.0);
        return DensityFunctions.lerp(bottomSlide, bottomTarget, afterTop);
    }

    /**
     * Wedding-cake: several thin horizontal strata with strong noise-warped surfaces (organic continuous
     * voids, not laser-flat), sparse mega stalagmite/stalactite columns bridging the gaps, and a sealed
     * bedrock floor so players cannot fall into the world-void kill.
     * <p>
     * Surfaces are cave/strata (stone/deepslate) — not overworld grass lawns under ceilings.
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
                weddingCakeSurface(),
                List.of(),
                -64, // no ocean fill — voids stay open
                false,
                false,
                false,
                false);
    }

    /**
     * Cave/strata dressing: bedrock apron, deepslate low, stone elsewhere.
     * No grass/dirt/bandlands — interiors must not read as overworld lawns under a ceiling.
     */
    private static SurfaceRules.RuleSource weddingCakeSurface() {
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRules.RuleSource deepslate = SurfaceRules.state(Blocks.DEEPSLATE.defaultBlockState());
        SurfaceRules.RuleSource bedrock = SurfaceRules.state(Blocks.BEDROCK.defaultBlockState());
        SurfaceRules.RuleSource gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        SurfaceRules.RuleSource cobble = SurfaceRules.state(Blocks.COBBLESTONE.defaultBlockState());

        SurfaceRules.RuleSource caveDressing = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.GRAVEL, -0.05, 0.05), gravel),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.35, 0.65), cobble),
                stone);

        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(
                        SurfaceRules.verticalGradient("calico_cake_bedrock", VerticalAnchor.bottom(), VerticalAnchor.aboveBottom(5)),
                        bedrock),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, caveDressing),
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, caveDressing),
                SurfaceRules.ifTrue(
                        SurfaceRules.verticalGradient("calico_cake_deepslate", VerticalAnchor.absolute(0), VerticalAnchor.absolute(8)),
                        deepslate),
                stone);
    }

    private static DensityFunction weddingCakeDensity(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Strong HORIZONTAL-ONLY warp: organic floors/ceilings without 3D cheese fins / mesa towers.
        DensityFunction surfaceWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.38, 0.0),
                DensityFunctions.constant(1.45));
        DensityFunction detailWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.60, 0.0),
                DensityFunctions.constant(0.65));
        DensityFunction jaggedWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.28, 0.0),
                DensityFunctions.constant(0.48));
        // High-frequency rim nibble — weathers plate edges / skirts (not clean geometric cuts).
        DensityFunction rimErosion = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 1.15, 0.0),
                DensityFunctions.constant(-0.55));
        DensityFunction warp = DensityFunctions.add(
                surfaceWarp,
                DensityFunctions.add(detailWarp, DensityFunctions.add(jaggedWarp, rimErosion)));

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
        // centerY, halfThickness, edgeSoftness — wider soft shells so rims weather organically (not stepped cuts)
        DensityFunction l1 = warpedBand(12, 8, 16, warp);   // ~ Y -12..36
        DensityFunction l2 = warpedBand(68, 6, 15, warp);   // ~ Y 47..89
        DensityFunction l3 = warpedBand(118, 5, 14, warp);  // ~ Y 99..137
        DensityFunction l4 = warpedBand(162, 5, 13, warp);  // ~ Y 144..180
        DensityFunction l5 = warpedBand(208, 4, 13, warp);  // ~ Y 191..225
        DensityFunction l6 = warpedBand(250, 4, 12, warp);  // ~ Y 234..266
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
     * Sparse mega stalagmite/stalactite columns (vanilla dripstone-mega vibe: wide bases,
     * tall continuity, weathered skirts). Soft threshold — no hard geometric cylinder cuts;
     * thin mesa/pencil fringes stay negative.
     */
    private static DensityFunction sparseMegaColumns(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Lower xz scale → wider body; low y scale → tall vertical continuity.
        DensityFunction pillar = DensityFunctions.noise(noises.getOrThrow(Noises.PILLAR), 3.5, 0.07);
        // Harsher rarity → fewer columns (still sparse).
        DensityFunction rarity = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -3.45);
        // High min thickness → fat mega dripstone, not skinny pillars.
        DensityFunction thickness = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_THICKNESS), 1.25, 3.0);
        DensityFunction shaped = DensityFunctions.add(
                DensityFunctions.mul(pillar, DensityFunctions.constant(2.9)),
                rarity);
        DensityFunction body = DensityFunctions.mul(shaped, thickness.cube());
        // Weathered skirt / irregular silhouette (horizontal nibble on the column shell).
        DensityFunction skirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.85, 0.0),
                DensityFunctions.constant(0.35));
        DensityFunction jaggedSkirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.55, 0.0),
                DensityFunctions.constant(0.22));
        DensityFunction raw = DensityFunctions.cacheOnce(
                DensityFunctions.add(body, DensityFunctions.add(skirt, jaggedSkirt)));
        // Soft gate (subtract threshold) keeps natural density falloff — not a laser-cut isosurface.
        return DensityFunctions.add(raw, DensityFunctions.constant(-0.10));
    }

    /** Same squeeze pipeline vanilla uses for final_density readability. */
    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        return DensityFunctions.mul(DensityFunctions.interpolated(blended), DensityFunctions.constant(0.64)).squeeze();
    }
}
