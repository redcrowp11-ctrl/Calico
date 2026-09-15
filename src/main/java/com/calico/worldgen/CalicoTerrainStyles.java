package com.calico.worldgen;

import java.util.List;

import com.calico.Calico;
import com.calico.config.TerrainStyle;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Resolves {@link TerrainStyle} → overworld {@link NoiseGeneratorSettings} for create-time LevelStem baking.
 * <ul>
 *   <li>{@link TerrainStyle#NORMAL} — vanilla overworld</li>
 *   <li>{@link TerrainStyle#SKY_ISLANDS} — archipelago of large floating islands (bulbous, no cones/pencils)</li>
 *   <li>{@link TerrainStyle#WEDDING_CAKE} — stacked strata, organic voids, sparse fat mega-columns</li>
 *   <li>Other styles — safe fallback to normal with an info log</li>
 * </ul>
 * <p>
 * Sky / cake settings are registered as {@code calico:sky_islands} / {@code calico:wedding_cake}
 * so LevelStem save/reload uses registry Holders (not {@code Holder.direct}, which can silently
 * fail to round-trip and fall back to vanilla overworld).
 */
public final class CalicoTerrainStyles {
    public static final ResourceKey<NoiseGeneratorSettings> SKY_ISLANDS = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "sky_islands"));
    public static final ResourceKey<NoiseGeneratorSettings> WEDDING_CAKE = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "wedding_cake"));

    private CalicoTerrainStyles() {
    }

    /** Datagen / datapack bootstrap for reload-safe noise settings. */
    public static void bootstrap(BootstrapContext<NoiseGeneratorSettings> context) {
        HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
        context.register(SKY_ISLANDS, buildSkyIslandsSettings(noises));
        context.register(WEDDING_CAKE, buildWeddingCakeSettings(noises));
    }

    /**
     * Picks noise settings for the overworld stem from {@code style}.
     * Always returns a usable holder (falls back to overworld on unknown / unimplemented).
     * Prefers registry holders for sky/cake so create + reload cannot ignore terrainStyle.
     */
    public static Holder<NoiseGeneratorSettings> resolveOverworldSettings(
            TerrainStyle style,
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            HolderGetter<NormalNoise.NoiseParameters> noises) {
        TerrainStyle resolved = style == null ? TerrainStyle.NORMAL : style;
        return switch (resolved) {
            case NORMAL -> noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            case SKY_ISLANDS -> {
                Calico.LOGGER.info("Calico: terrainStyle=sky_islands → archipelago floating-island density ({})",
                        SKY_ISLANDS.location());
                yield noiseSettings.get(SKY_ISLANDS)
                        .<Holder<NoiseGeneratorSettings>>map(h -> h)
                        .orElseGet(() -> {
                            Calico.LOGGER.warn(
                                    "Calico: {} missing from registry — baking Holder.direct fallback (reload may lose style)",
                                    SKY_ISLANDS.location());
                            return Holder.direct(buildSkyIslandsSettings(noises));
                        });
            }
            case WEDDING_CAKE -> {
                Calico.LOGGER.info("Calico: terrainStyle=wedding_cake → stacked-strata density ({})",
                        WEDDING_CAKE.location());
                yield noiseSettings.get(WEDDING_CAKE)
                        .<Holder<NoiseGeneratorSettings>>map(h -> h)
                        .orElseGet(() -> {
                            Calico.LOGGER.warn(
                                    "Calico: {} missing from registry — baking Holder.direct fallback (reload may lose style)",
                                    WEDDING_CAKE.location());
                            return Holder.direct(buildWeddingCakeSettings(noises));
                        });
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
     * Sky islands: archipelago of large floating landmasses with bulbous/rounded undersides.
     * <p>
     * Avoids vanilla {@code end_islands} (one huge spawn island; outer islands ~1024+ blocks away)
     * and avoids End 3D cheese / distance cones that read as skinny pencils + inverted-cone hangers.
     */
    static NoiseGeneratorSettings buildSkyIslandsSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction islands2d = DensityFunctions.cache2d(archipelagoIslandSelector(noises));
        // Soft bulbous lens — long underside ramp (no pointed cones), rounded top
        DensityFunction bellyFloor = DensityFunctions.yClampedGradient(52, 92, -1.0, 1.0);
        DensityFunction bellyCeil = DensityFunctions.yClampedGradient(118, 152, 1.0, -1.0);
        DensityFunction lens = DensityFunctions.min(bellyFloor, bellyCeil);
        // Mild horizontal-only nibble on surfaces — NOT 3D cheese (pencils / hanging spikes)
        DensityFunction surfaceNibble = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.85, 0.0),
                DensityFunctions.constant(0.10));
        DensityFunction edgeWeather = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.EROSION), 1.1, 0.0),
                DensityFunctions.constant(0.08));
        // islands2d positive on land; amplify so gaps stay void while island cores stay fat
        DensityFunction body = DensityFunctions.add(
                DensityFunctions.add(lens, DensityFunctions.mul(islands2d, DensityFunctions.constant(1.65))),
                DensityFunctions.add(surfaceNibble, edgeWeather));

        DensityFunction finalDensity = postProcess(slideSky(body));
        DensityFunction initial = slideSky(DensityFunctions.add(islands2d, DensityFunctions.constant(-0.35)));

        NoiseRouter router = new NoiseRouter(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                islands2d,
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                initial,
                finalDensity,
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero());

        return new NoiseGeneratorSettings(
                NoiseSettings.create(0, 256, 2, 1),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                router,
                net.minecraft.data.worldgen.SurfaceRuleData.overworldLike(false, false, false),
                List.of(),
                -64,
                false,
                false,
                false,
                false);
    }

    /**
     * 2D archipelago selector: large islands (low-frequency continental blobs) with neighbors
     * visible within normal explore/render distance — not End solitude.
     */
    private static DensityFunction archipelagoIslandSelector(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Lower xz scale → larger islands. ~0.28–0.40 keeps big landmasses with nearby neighbors.
        DensityFunction primary = DensityFunctions.noise(noises.getOrThrow(Noises.CONTINENTALNESS), 0.32, 0.0);
        DensityFunction secondary = DensityFunctions.noise(noises.getOrThrow(Noises.EROSION), 0.48, 0.0);
        DensityFunction detail = DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.95, 0.0);
        DensityFunction blended = DensityFunctions.add(
                DensityFunctions.mul(primary, DensityFunctions.constant(1.05)),
                DensityFunctions.add(
                        DensityFunctions.mul(secondary, DensityFunctions.constant(0.38)),
                        DensityFunctions.mul(detail, DensityFunctions.constant(0.12))));
        // Slight land bias so islands are generous in size while gaps still open between them
        return DensityFunctions.add(blended, DensityFunctions.constant(0.08));
    }

    /** Gentle vertical slide for sky band — softer than End so undersides stay bulbous. */
    private static DensityFunction slideSky(DensityFunction density) {
        return slide(density, 0, 256, 48, -40, -0.15, 8, 40, -0.15);
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
     * Wedding-cake: thin horizontal strata with strong noise-warped surfaces (organic continuous
     * voids, not laser-flat), sparse mega dripstone trunks bridging the gaps, and a sealed
     * bedrock floor so players cannot fall into the world-void kill.
     */
    static NoiseGeneratorSettings buildWeddingCakeSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction finalDensity = postProcess(weddingCakeDensity(noises));
        DensityFunction initial = DensityFunctions.max(
                DensityFunctions.max(weddingCakeLayerBands(DensityFunctions.zero(), noises), sealedWorldFloor()),
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
                -64,
                false,
                false,
                false,
                false);
    }

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
        DensityFunction surfaceWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.38, 0.0),
                DensityFunctions.constant(1.55));
        DensityFunction detailWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.60, 0.0),
                DensityFunctions.constant(0.72));
        DensityFunction jaggedWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.28, 0.0),
                DensityFunctions.constant(0.55));
        DensityFunction rimErosion = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 1.15, 0.0),
                DensityFunctions.constant(-0.62));
        DensityFunction irregular = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.EROSION), 0.42, 0.0),
                DensityFunctions.constant(0.40));
        DensityFunction warp = DensityFunctions.add(
                surfaceWarp,
                DensityFunctions.add(detailWarp,
                        DensityFunctions.add(jaggedWarp, DensityFunctions.add(rimErosion, irregular))));

        DensityFunction layers = weddingCakeLayerBands(warp, noises);
        DensityFunction columns = sparseMegaColumns(noises);
        return DensityFunctions.max(DensityFunctions.max(layers, columns), sealedWorldFloor());
    }

    private static DensityFunction sealedWorldFloor() {
        return DensityFunctions.yClampedGradient(-52, -44, 1.0, -1.0);
    }

    /**
     * Six stacked plates with large voids. Bottom plate gets extra roughness so it is not a
     * billiard-table slab above the void seal.
     */
    private static DensityFunction weddingCakeLayerBands(
            DensityFunction warp, HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction bottomRough = DensityFunctions.add(
                warp,
                DensityFunctions.add(
                        DensityFunctions.mul(
                                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.22, 0.0),
                                DensityFunctions.constant(1.15)),
                        DensityFunctions.mul(
                                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.40, 0.0),
                                DensityFunctions.constant(0.70))));
        // Wider soft shells + uneven thicknesses — weathered arches, not CAD slabs
        DensityFunction l1 = warpedBand(12, 9, 22, bottomRough);  // rough bottom plate above void
        DensityFunction l2 = warpedBand(68, 6, 17, warp);
        DensityFunction l3 = warpedBand(118, 5, 16, warp);
        DensityFunction l4 = warpedBand(162, 6, 15, warp);
        DensityFunction l5 = warpedBand(208, 4, 14, warp);
        DensityFunction l6 = warpedBand(250, 4, 13, warp);
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
     * Sparse FAT mega dripstone trunks only — high thickness + harsh fringe gate kills pencil spikes.
     */
    private static DensityFunction sparseMegaColumns(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Low xz scale → wide trunks; very low y scale → tall continuity
        DensityFunction pillar = DensityFunctions.noise(noises.getOrThrow(Noises.PILLAR), 1.85, 0.045);
        DensityFunction rarity = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -3.85);
        // High min thickness → true mega trunks, not skinny pencils
        DensityFunction thickness = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_THICKNESS), 2.35, 4.8);
        DensityFunction shaped = DensityFunctions.add(
                DensityFunctions.mul(pillar, DensityFunctions.constant(3.4)),
                rarity);
        DensityFunction body = DensityFunctions.mul(shaped, thickness.cube());
        DensityFunction skirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.75, 0.0),
                DensityFunctions.constant(0.42));
        DensityFunction jaggedSkirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.50, 0.0),
                DensityFunctions.constant(0.28));
        DensityFunction raw = DensityFunctions.cacheOnce(
                DensityFunctions.add(body, DensityFunctions.add(skirt, jaggedSkirt)));
        // Harsh negative gate — thin fringes / pencil spikes go negative and disappear
        return DensityFunctions.add(raw, DensityFunctions.constant(-0.55));
    }

    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        return DensityFunctions.mul(DensityFunctions.interpolated(blended), DensityFunctions.constant(0.64)).squeeze();
    }
}
