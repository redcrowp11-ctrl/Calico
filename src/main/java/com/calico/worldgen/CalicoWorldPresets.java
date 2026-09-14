package com.calico.worldgen;

import java.util.Map;
import java.util.Set;

import com.calico.Calico;
import com.calico.config.CalicoWorldGenConfig;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
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
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.minecraft.core.RegistrySetBuilder;

/**
 * Calico world preset / world-type registration hooks for multi-biome selection.
 * <p>
 * Datagen + datapack JSON register {@code calico:calico} so it appears in the create-world
 * world-type list. Runtime helpers build a {@link WeightedBiomeSource} from
 * {@link CalicoWorldGenConfig} + world seed for the actual selection.
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
        BiomeSource source = WeightedBiomeSource.fromConfig(config, worldSeed, biomes);
        Holder<NoiseGeneratorSettings> settings = noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        Holder<DimensionType> dimType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        return new LevelStem(dimType, new NoiseBasedChunkGenerator(source, settings));
    }

    /** Full WorldPreset map: Calico overworld + vanilla-style Nether/End. */
    public static WorldPreset createPreset(
            CalicoWorldGenConfig config,
            long worldSeed,
            HolderGetter<Biome> biomes,
            HolderGetter<DimensionType> dimensionTypes,
            HolderGetter<NoiseGeneratorSettings> noiseSettings,
            HolderGetter<MultiNoiseBiomeSourceParameterList> multiNoiseLists) {
        LevelStem overworld = createOverworldStem(config, worldSeed, biomes, dimensionTypes, noiseSettings);

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
