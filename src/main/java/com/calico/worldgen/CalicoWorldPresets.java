package com.calico.worldgen;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.calico.Calico;
import com.calico.config.CalicoCreateTimeConfig;
import com.calico.config.CalicoWorldGenConfig;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.minecraft.core.RegistrySetBuilder;

/**
 * Calico world preset / world-type registration hooks for multi-biome selection.
 * <p>
 * Datagen + datapack JSON register {@code calico:calico} so it appears in the create-world
 * world-type list. Runtime helpers bake {@link CalicoWorldGenConfig} into {@link LevelStem}
 * biome sources (create-time config → WeightedBiomeSource).
 */
public final class CalicoWorldPresets {
    public static final ResourceKey<WorldPreset> CALICO = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "calico"));

    private CalicoWorldPresets() {
    }

    /** Builds an overworld stem using Calico weighted biomes from config + seed. */
    public static LevelStem createOverworldStem(
            CalicoWorldGenConfig config,
            long worldSeed,
            HolderGetter<Biome> biomes,
            HolderGetter<DimensionType> dimensionTypes,
            HolderGetter<NoiseGeneratorSettings> noiseSettings) {
        BiomeSource source = WeightedBiomeSource.fromConfig(config, worldSeed, biomes, BiomeDimension.OVERWORLD);
        Holder<NoiseGeneratorSettings> settings = noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        Holder<DimensionType> dimType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        return new LevelStem(dimType, new NoiseBasedChunkGenerator(source, settings));
    }

    /**
     * Rebuilds {@code dimensions} so the overworld (and optionally Nether/End) LevelStem
     * biome sources come from create-time {@code config} + {@code worldSeed}.
     * <p>
     * Overworld requires at least one in-scope biome. Nether/End use weighted sources when
     * the config has matching biomes; otherwise vanilla generators are kept.
     */
    public static WorldDimensions applyCreateTimeConfig(
            RegistryAccess registries,
            WorldDimensions dimensions,
            CalicoWorldGenConfig config,
            long worldSeed) {
        HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        HolderGetter<NoiseGeneratorSettings> noiseSettings = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        HolderGetter<MultiNoiseBiomeSourceParameterList> multiNoiseLists =
                registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        HolderGetter<DimensionType> dimensionTypes = registries.lookupOrThrow(Registries.DIMENSION_TYPE);

        WeightedBiomeSource overworldSource =
                WeightedBiomeSource.fromConfig(config, worldSeed, biomes, BiomeDimension.OVERWORLD);
        ChunkGenerator overworldGen = new NoiseBasedChunkGenerator(
                overworldSource, noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD));
        WorldDimensions result = dimensions.replaceOverworldGenerator(registries, overworldGen);

        Map<ResourceKey<LevelStem>, LevelStem> map = new java.util.LinkedHashMap<>(result.dimensions());

        Optional<WeightedBiomeSource> netherWeighted =
                WeightedBiomeSource.tryFromConfig(config, worldSeed, biomes, BiomeDimension.NETHER);
        if (netherWeighted.isPresent()) {
            map.put(LevelStem.NETHER, new LevelStem(
                    dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
                    new NoiseBasedChunkGenerator(
                            netherWeighted.get(),
                            noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER))));
            Calico.LOGGER.info("Calico: applied create-time config to Nether LevelStem ({} biomes)",
                    netherWeighted.get().entries().size());
        }

        Optional<WeightedBiomeSource> endWeighted =
                WeightedBiomeSource.tryFromConfig(config, worldSeed, biomes, BiomeDimension.END);
        if (endWeighted.isPresent()) {
            map.put(LevelStem.END, new LevelStem(
                    dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
                    new NoiseBasedChunkGenerator(
                            endWeighted.get(),
                            noiseSettings.getOrThrow(NoiseGeneratorSettings.END))));
            Calico.LOGGER.info("Calico: applied create-time config to End LevelStem ({} biomes)",
                    endWeighted.get().entries().size());
        } else {
            // Keep existing End (or ensure vanilla TheEndBiomeSource if missing).
            map.computeIfAbsent(LevelStem.END, k -> new LevelStem(
                    dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
                    new NoiseBasedChunkGenerator(
                            TheEndBiomeSource.create(biomes),
                            noiseSettings.getOrThrow(NoiseGeneratorSettings.END))));
        }

        // If nether was not overridden, ensure vanilla multi-noise remains when absent.
        map.computeIfAbsent(LevelStem.NETHER, k -> {
            Holder<MultiNoiseBiomeSourceParameterList> netherParams =
                    multiNoiseLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            return new LevelStem(
                    dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
                    new NoiseBasedChunkGenerator(
                            MultiNoiseBiomeSource.createFromPreset(netherParams),
                            noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)));
        });

        Calico.LOGGER.info("Calico: applied create-time config to Overworld LevelStem ({} biomes)",
                overworldSource.entries().size());
        return new WorldDimensions(map);
    }

    /**
     * Applies {@link CalicoCreateTimeConfig#peekValidPending()} into {@code dimensions}
     * when present; otherwise returns {@code dimensions} unchanged.
     */
    public static WorldDimensions applyPendingCreateTimeConfig(
            RegistryAccess registries,
            WorldDimensions dimensions,
            long worldSeed) {
        Optional<CalicoWorldGenConfig> pending = CalicoCreateTimeConfig.peekValidPending();
        if (pending.isEmpty()) {
            Calico.LOGGER.debug("Calico: no valid create-time config pending; leaving LevelStem unchanged");
            return dimensions;
        }
        return applyCreateTimeConfig(registries, dimensions, pending.get(), worldSeed);
    }

    /** Full WorldPreset map: Calico overworld + config-scoped Nether/End or vanilla fallbacks. */
    public static WorldPreset createPreset(
            CalicoWorldGenConfig config,
            long worldSeed,
            HolderGetter<Biome> biomes,
            HolderGetter<DimensionType> dimensionTypes,
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            HolderGetter<MultiNoiseBiomeSourceParameterList> multiNoiseLists) {
        LevelStem overworld = createOverworldStem(config, worldSeed, biomes, dimensionTypes, noiseSettings);

        LevelStem nether = WeightedBiomeSource.tryFromConfig(config, worldSeed, biomes, BiomeDimension.NETHER)
                .map(src -> new LevelStem(
                        dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
                        new NoiseBasedChunkGenerator(src, noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER))))
                .orElseGet(() -> {
                    Holder<MultiNoiseBiomeSourceParameterList> netherParams =
                            multiNoiseLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
                    return new LevelStem(
                            dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
                            new NoiseBasedChunkGenerator(
                                    MultiNoiseBiomeSource.createFromPreset(netherParams),
                                    noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)));
                });

        LevelStem end = WeightedBiomeSource.tryFromConfig(config, worldSeed, biomes, BiomeDimension.END)
                .map(src -> new LevelStem(
                        dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
                        new NoiseBasedChunkGenerator(src, noiseSettings.getOrThrow(NoiseGeneratorSettings.END))))
                .orElseGet(() -> new LevelStem(
                        dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
                        new NoiseBasedChunkGenerator(
                                TheEndBiomeSource.create(biomes),
                                noiseSettings.getOrThrow(NoiseGeneratorSettings.END))));

        return new WorldPreset(Map.of(
                LevelStem.OVERWORLD, overworld,
                LevelStem.NETHER, nether,
                LevelStem.END, end));
    }

    /** Datagen bootstrap: default preset uses plains @ weight 1.0 (seed 0 placeholder). */
    public static void bootstrap(BootstrapContext<WorldPreset> context) {
        HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);
        HolderGetter<DimensionType> dimensionTypes = context.lookup(Registries.DIMENSION_TYPE);
        HolderGetter<NoiseGeneratorSettings> noiseSettings = context.lookup(Registries.NOISE_SETTINGS);
        HolderGetter<MultiNoiseBiomeSourceParameterList> multiNoiseLists =
                context.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

        Holder<Biome> plains = biomes.getOrThrow(
                ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("plains")));
        WeightedBiomeSource defaultSource = new WeightedBiomeSource(0L,
                java.util.List.of(new WeightedBiomeEntry(plains, 1.0d)));

        LevelStem overworld = new LevelStem(
                dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD),
                new NoiseBasedChunkGenerator(
                        defaultSource,
                        noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD)));

        Holder<MultiNoiseBiomeSourceParameterList> netherParams =
                multiNoiseLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
        LevelStem nether = new LevelStem(
                dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
                new NoiseBasedChunkGenerator(
                        MultiNoiseBiomeSource.createFromPreset(netherParams),
                        noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)));

        LevelStem end = new LevelStem(
                dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
                new NoiseBasedChunkGenerator(
                        TheEndBiomeSource.create(biomes),
                        noiseSettings.getOrThrow(NoiseGeneratorSettings.END)));

        context.register(CALICO, new WorldPreset(Map.of(
                LevelStem.OVERWORLD, overworld,
                LevelStem.NETHER, nether,
                LevelStem.END, end)));
    }

    public static void addDatagenProviders(GatherDataEvent event) {
        net.minecraft.data.DataProvider.Factory<DatapackBuiltinEntriesProvider> factory = output -> new DatapackBuiltinEntriesProvider(
                output,
                event.getLookupProvider(),
                new RegistrySetBuilder().add(Registries.WORLD_PRESET, CalicoWorldPresets::bootstrap),
                Set.of(Calico.MOD_ID));
        event.getGenerator().addProvider(event.includeServer(), factory);
    }
}
