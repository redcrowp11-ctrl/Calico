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
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Resolves {@link TerrainStyle} → overworld {@link NoiseGeneratorSettings} for create-time LevelStem baking.
 * <ul>
 *   <li>{@link TerrainStyle#NORMAL} — vanilla overworld</li>
 *   <li>{@link TerrainStyle#SKY_ISLANDS} — End-like floating islands over void (playtest baseline 7fec4e8)</li>
 *   <li>{@link TerrainStyle#ISLANDS} — regular sea-level islands</li>
 *   <li>{@link TerrainStyle#BIG_ISLANDS} — larger sea-level islands</li>
 *   <li>{@link TerrainStyle#MOUNTAINOUS} — vanilla amplified (tall mountains)</li>
 *   <li>{@link TerrainStyle#CAVE} — vanilla caves worldgen</li>
 *   <li>{@link TerrainStyle#WEDDING_CAKE} — layered voids + thick columns (playtest baseline 7fec4e8)</li>
 *   <li>{@link TerrainStyle#ANT_HILL} — smooth tall hills with dense interconnect tunnels</li>
 * </ul>
 * Custom styles register as {@code calico:*} so LevelStem save/reload uses registry Holders.
 */
public final class CalicoTerrainStyles {
    public static final ResourceKey<NoiseGeneratorSettings> SKY_ISLANDS = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "sky_islands"));
    public static final ResourceKey<NoiseGeneratorSettings> WEDDING_CAKE = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "wedding_cake"));
    public static final ResourceKey<NoiseGeneratorSettings> ISLANDS = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "islands"));
    public static final ResourceKey<NoiseGeneratorSettings> BIG_ISLANDS = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "big_islands"));
    public static final ResourceKey<NoiseGeneratorSettings> ANT_HILL = ResourceKey.create(
            Registries.NOISE_SETTINGS,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "ant_hill"));

    private CalicoTerrainStyles() {
    }

    /** Datagen / datapack bootstrap for reload-safe noise settings. */
    public static void bootstrap(BootstrapContext<NoiseGeneratorSettings> context) {
        HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
        context.register(SKY_ISLANDS, buildSkyIslandsSettings());
        context.register(WEDDING_CAKE, buildWeddingCakeSettings(noises));
        context.register(ISLANDS, buildSeaIslandsSettings(noises, 0.42, 0.08, 18.0));
        context.register(BIG_ISLANDS, buildSeaIslandsSettings(noises, 0.22, 0.14, 28.0));
        context.register(ANT_HILL, buildAntHillSettings(noises));
    }

    /**
     * Picks noise settings for the overworld stem from {@code style}.
     * Always returns a usable holder (falls back to overworld on unknown).
     */
    public static Holder<NoiseGeneratorSettings> resolveOverworldSettings(
            TerrainStyle style,
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            HolderGetter<NormalNoise.NoiseParameters> noises) {
        TerrainStyle resolved = style == null ? TerrainStyle.NORMAL : style;
        return switch (resolved) {
            case NORMAL -> noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            case SKY_ISLANDS -> registryOrDirect(
                    noiseSettings, SKY_ISLANDS, "sky_islands → end-like floating island density",
                    CalicoTerrainStyles::buildSkyIslandsSettings);
            case WEDDING_CAKE -> registryOrDirect(
                    noiseSettings, WEDDING_CAKE, "wedding_cake → stacked-strata density",
                    () -> buildWeddingCakeSettings(noises));
            case ISLANDS -> registryOrDirect(
                    noiseSettings, ISLANDS, "islands → sea-level island density",
                    () -> buildSeaIslandsSettings(noises, 0.42, 0.08, 18.0));
            case BIG_ISLANDS -> registryOrDirect(
                    noiseSettings, BIG_ISLANDS, "big_islands → large sea-level island density",
                    () -> buildSeaIslandsSettings(noises, 0.22, 0.14, 28.0));
            case MOUNTAINOUS -> {
                Calico.LOGGER.info("Calico: terrainStyle=mountainous → vanilla amplified ({})",
                        NoiseGeneratorSettings.AMPLIFIED.location());
                yield noiseSettings.getOrThrow(NoiseGeneratorSettings.AMPLIFIED);
            }
            case CAVE -> {
                Calico.LOGGER.info("Calico: terrainStyle=cave → vanilla caves ({})",
                        NoiseGeneratorSettings.CAVES.location());
                yield noiseSettings.getOrThrow(NoiseGeneratorSettings.CAVES);
            }
            case ANT_HILL -> registryOrDirect(
                    noiseSettings, ANT_HILL, "ant_hill → tall smooth hills + tunnel nest",
                    () -> buildAntHillSettings(noises));
        };
    }

    @FunctionalInterface
    private interface SettingsFactory {
        NoiseGeneratorSettings get();
    }

    private static Holder<NoiseGeneratorSettings> registryOrDirect(
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            ResourceKey<NoiseGeneratorSettings> key,
            String logLabel,
            SettingsFactory factory) {
        Calico.LOGGER.info("Calico: terrainStyle={} ({})", logLabel, key.location());
        return noiseSettings.get(key)
                .<Holder<NoiseGeneratorSettings>>map(h -> h)
                .orElseGet(() -> {
                    Calico.LOGGER.warn(
                            "Calico: {} missing from registry — baking Holder.direct fallback (reload may lose style)",
                            key.location());
                    return Holder.direct(factory.get());
                });
    }

    /**
     * Sky islands: discrete End-like floating landmasses (playtest baseline {@code 7fec4e8}).
     * Uses {@code end_islands} + End base_3d_noise — not vanilla floating_islands cheese alone.
     */
    static NoiseGeneratorSettings buildSkyIslandsSettings() {
        DensityFunction base3d = BlendedNoise.createUnseeded(0.25, 0.25, 80.0, 160.0, 4.0);
        DensityFunction islands = DensityFunctions.cache2d(DensityFunctions.endIslands(0L));
        DensityFunction slopedCheese = DensityFunctions.add(DensityFunctions.endIslands(0L), base3d);

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
                islands,
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
                true);
    }

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
     * Sea-level islands: 2D land mask over shallow ocean floor; aquifers + seaLevel 63.
     *
     * @param xzScale lower → larger islands
     * @param landBias positive bias grows land coverage
     * @param heightAmp how tall island interiors rise above sea level
     */
    static NoiseGeneratorSettings buildSeaIslandsSettings(
            HolderGetter<NormalNoise.NoiseParameters> noises,
            double xzScale,
            double landBias,
            double heightAmp) {
        DensityFunction selector = DensityFunctions.cache2d(seaIslandSelector(noises, xzScale, landBias));
        DensityFunction landMask = DensityFunctions.max(selector, DensityFunctions.constant(0.0));

        // Ocean floor everywhere (~Y 32–48)
        DensityFunction oceanFloor = DensityFunctions.yClampedGradient(28, 48, 1.0, -1.0);
        // Island body: rises from ~Y 50 to a rounded top when landMask > 0
        DensityFunction islandFloor = DensityFunctions.yClampedGradient(50, 62, -1.0, 1.0);
        DensityFunction islandCeil = DensityFunctions.yClampedGradient(70, 70 + (int) heightAmp, 1.0, -1.0);
        DensityFunction islandLens = DensityFunctions.min(islandFloor, islandCeil);
        DensityFunction islandCore = DensityFunctions.mul(
                DensityFunctions.add(islandLens, DensityFunctions.constant(0.15)),
                DensityFunctions.mul(landMask, DensityFunctions.constant(1.9)));
        DensityFunction surfaceNibble = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.9, 0.0),
                DensityFunctions.mul(landMask, DensityFunctions.constant(0.14)));
        DensityFunction body = DensityFunctions.max(
                oceanFloor,
                DensityFunctions.add(islandCore, surfaceNibble));

        DensityFunction finalDensity = postProcess(slide(body, -64, 384, 80, 0, -0.2, 4, 32, -0.1));
        DensityFunction initial = DensityFunctions.max(
                oceanFloor,
                DensityFunctions.mul(landMask, DensityFunctions.constant(0.45)));

        NoiseRouter router = new NoiseRouter(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                selector,
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
                net.minecraft.data.worldgen.SurfaceRuleData.overworld(),
                List.of(),
                63,
                false,
                true,
                false,
                false);
    }

    private static DensityFunction seaIslandSelector(
            HolderGetter<NormalNoise.NoiseParameters> noises, double xzScale, double landBias) {
        DensityFunction primary = DensityFunctions.noise(noises.getOrThrow(Noises.CONTINENTALNESS), xzScale, 0.0);
        DensityFunction secondary = DensityFunctions.noise(
                noises.getOrThrow(Noises.EROSION), xzScale * 1.4, 0.0);
        DensityFunction detail = DensityFunctions.noise(
                noises.getOrThrow(Noises.SURFACE), xzScale * 2.8, 0.0);
        DensityFunction blended = DensityFunctions.add(
                DensityFunctions.mul(primary, DensityFunctions.constant(1.05)),
                DensityFunctions.add(
                        DensityFunctions.mul(secondary, DensityFunctions.constant(0.35)),
                        DensityFunctions.mul(detail, DensityFunctions.constant(0.10))));
        return DensityFunctions.add(blended, DensityFunctions.constant(landBias));
    }

    /**
     * Ant hill: smooth tall mountainous mass with dense interconnecting tunnels (ant-nest vibe).
     */
    static NoiseGeneratorSettings buildAntHillSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        // Smooth low-frequency height field → tall rolling mountains (not jagged amplified)
        DensityFunction hills = DensityFunctions.cache2d(
                DensityFunctions.noise(noises.getOrThrow(Noises.CONTINENTALNESS), 0.18, 0.0));
        DensityFunction roll = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.EROSION), 0.28, 0.0),
                DensityFunctions.constant(0.35));
        DensityFunction heightField = DensityFunctions.add(hills, roll);

        // Base solid rises high: floor near Y 40, peaks toward Y 180–220 when heightField positive
        DensityFunction baseFloor = DensityFunctions.yClampedGradient(32, 55, 1.0, 0.2);
        DensityFunction peakBoost = DensityFunctions.mul(
                DensityFunctions.add(heightField, DensityFunctions.constant(0.55)),
                DensityFunctions.constant(1.35));
        DensityFunction peakCeil = DensityFunctions.yClampedGradient(120, 220, 1.0, -1.0);
        DensityFunction mountain = DensityFunctions.add(
                DensityFunctions.min(baseFloor, DensityFunctions.add(peakCeil, peakBoost)),
                DensityFunctions.constant(0.25));

        // Dense interconnect tunnels — cheese + noodle-like horizontal tubes
        DensityFunction cheese = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.55, 0.55),
                DensityFunctions.constant(1.15));
        DensityFunction tubesA = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SPAGHETTI_3D_1), 0.70, 0.35),
                DensityFunctions.constant(0.95));
        DensityFunction tubesB = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SPAGHETTI_3D_2), 0.55, 0.45),
                DensityFunctions.constant(0.85));
        DensityFunction fineTunnels = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_LAYER), 0.90, 0.20),
                DensityFunctions.constant(0.55));
        DensityFunction tunnels = DensityFunctions.add(
                cheese,
                DensityFunctions.add(tubesA, DensityFunctions.add(tubesB, fineTunnels)));

        // Carve: keep mountain positive, subtract tunnel strength (gate so surface skin stays intact)
        DensityFunction tunnelGate = DensityFunctions.yClampedGradient(50, 70, 0.15, 1.0);
        DensityFunction carved = DensityFunctions.add(
                mountain,
                DensityFunctions.mul(
                        DensityFunctions.mul(tunnels, DensityFunctions.constant(-1.0)),
                        tunnelGate));
        DensityFunction sealedFloor = DensityFunctions.yClampedGradient(-60, -52, 1.0, -1.0);
        DensityFunction body = DensityFunctions.max(carved, sealedFloor);

        DensityFunction finalDensity = postProcess(slide(body, -64, 384, 64, 0, -0.15, 4, 24, -0.1));
        DensityFunction initial = DensityFunctions.max(
                DensityFunctions.add(baseFloor, DensityFunctions.constant(-0.2)),
                sealedFloor);

        NoiseRouter router = new NoiseRouter(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                hills,
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
                antHillSurface(),
                List.of(),
                63,
                false,
                true,
                false,
                false);
    }

    private static SurfaceRules.RuleSource antHillSurface() {
        SurfaceRules.RuleSource grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceRules.RuleSource dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRules.RuleSource deepslate = SurfaceRules.state(Blocks.DEEPSLATE.defaultBlockState());
        SurfaceRules.RuleSource bedrock = SurfaceRules.state(Blocks.BEDROCK.defaultBlockState());
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(
                        SurfaceRules.verticalGradient(
                                "calico_ant_bedrock", VerticalAnchor.bottom(), VerticalAnchor.aboveBottom(5)),
                        bedrock),
                SurfaceRules.ifTrue(
                        SurfaceRules.ON_FLOOR,
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0), grass),
                                dirt)),
                SurfaceRules.ifTrue(
                        SurfaceRules.verticalGradient(
                                "calico_ant_deepslate", VerticalAnchor.absolute(0), VerticalAnchor.absolute(8)),
                        deepslate),
                stone);
    }

    /**
     * Wedding-cake: thin stacked strata + organic voids + fat mega columns (baseline {@code 7fec4e8}).
     */
    static NoiseGeneratorSettings buildWeddingCakeSettings(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction finalDensity = postProcess(weddingCakeDensity(noises));
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
                        SurfaceRules.verticalGradient(
                                "calico_cake_bedrock", VerticalAnchor.bottom(), VerticalAnchor.aboveBottom(5)),
                        bedrock),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, caveDressing),
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, caveDressing),
                SurfaceRules.ifTrue(
                        SurfaceRules.verticalGradient(
                                "calico_cake_deepslate", VerticalAnchor.absolute(0), VerticalAnchor.absolute(8)),
                        deepslate),
                stone);
    }

    private static DensityFunction weddingCakeDensity(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction surfaceWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.38, 0.0),
                DensityFunctions.constant(1.45));
        DensityFunction detailWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.60, 0.0),
                DensityFunctions.constant(0.65));
        DensityFunction jaggedWarp = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.28, 0.0),
                DensityFunctions.constant(0.48));
        DensityFunction rimErosion = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 1.15, 0.0),
                DensityFunctions.constant(-0.55));
        DensityFunction warp = DensityFunctions.add(
                surfaceWarp,
                DensityFunctions.add(detailWarp, DensityFunctions.add(jaggedWarp, rimErosion)));

        DensityFunction layers = weddingCakeLayerBands(warp);
        DensityFunction columns = sparseMegaColumns(noises);
        return DensityFunctions.max(DensityFunctions.max(layers, columns), sealedWorldFloor());
    }

    private static DensityFunction sealedWorldFloor() {
        return DensityFunctions.yClampedGradient(-52, -44, 1.0, -1.0);
    }

    private static DensityFunction weddingCakeLayerBands(DensityFunction warp) {
        DensityFunction l1 = warpedBand(12, 8, 16, warp);
        DensityFunction l2 = warpedBand(68, 6, 15, warp);
        DensityFunction l3 = warpedBand(118, 5, 14, warp);
        DensityFunction l4 = warpedBand(162, 5, 13, warp);
        DensityFunction l5 = warpedBand(208, 4, 13, warp);
        DensityFunction l6 = warpedBand(250, 4, 12, warp);
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

    private static DensityFunction sparseMegaColumns(HolderGetter<NormalNoise.NoiseParameters> noises) {
        DensityFunction pillar = DensityFunctions.noise(noises.getOrThrow(Noises.PILLAR), 3.5, 0.07);
        DensityFunction rarity = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -3.45);
        DensityFunction thickness = DensityFunctions.mappedNoise(noises.getOrThrow(Noises.PILLAR_THICKNESS), 1.25, 3.0);
        DensityFunction shaped = DensityFunctions.add(
                DensityFunctions.mul(pillar, DensityFunctions.constant(2.9)),
                rarity);
        DensityFunction body = DensityFunctions.mul(shaped, thickness.cube());
        DensityFunction skirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 0.85, 0.0),
                DensityFunctions.constant(0.35));
        DensityFunction jaggedSkirt = DensityFunctions.mul(
                DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 0.55, 0.0),
                DensityFunctions.constant(0.22));
        DensityFunction raw = DensityFunctions.cacheOnce(
                DensityFunctions.add(body, DensityFunctions.add(skirt, jaggedSkirt)));
        return DensityFunctions.add(raw, DensityFunctions.constant(-0.10));
    }

    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        return DensityFunctions.mul(DensityFunctions.interpolated(blended), DensityFunctions.constant(0.64)).squeeze();
    }
}
