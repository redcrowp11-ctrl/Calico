package com.calico.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.calico.Calico;
import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.config.SelectedBiomeEntry;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Weighted multi-biome {@link BiomeSource}.
 * <ul>
 *   <li>Any subset of biomes with per-biome weights</li>
 *   <li>Deterministic from world {@code seed} + quart-position hash</li>
 *   <li>Single biome ⇒ always that biome (100%)</li>
 * </ul>
 */
public class WeightedBiomeSource extends BiomeSource {
    public static final MapCodec<WeightedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            com.mojang.serialization.Codec.LONG.fieldOf("seed").forGetter(WeightedBiomeSource::seed),
            WeightedBiomeEntry.CODEC.listOf().fieldOf("biomes").forGetter(WeightedBiomeSource::entries)
    ).apply(instance, WeightedBiomeSource::new));

    private final long seed;
    private final List<WeightedBiomeEntry> entries;
    private final double totalWeight;
    private final Holder<Biome>[] biomes;
    private final double[] cumulative;

    @SuppressWarnings("unchecked")
    public WeightedBiomeSource(long seed, List<WeightedBiomeEntry> entries) {
        this.seed = seed;
        List<WeightedBiomeEntry> cleaned = new ArrayList<>();
        for (WeightedBiomeEntry e : entries) {
            if (e != null && e.biome() != null && Double.isFinite(e.weight()) && e.weight() > 0.0d) {
                cleaned.add(e);
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("WeightedBiomeSource requires at least one biome with weight > 0");
        }
        this.entries = List.copyOf(cleaned);
        this.biomes = new Holder[cleaned.size()];
        this.cumulative = new double[cleaned.size()];
        double sum = 0.0d;
        for (int i = 0; i < cleaned.size(); i++) {
            WeightedBiomeEntry e = cleaned.get(i);
            this.biomes[i] = e.biome();
            sum += e.weight();
            this.cumulative[i] = sum;
        }
        this.totalWeight = sum;
    }

    public long seed() {
        return seed;
    }

    public List<WeightedBiomeEntry> entries() {
        return entries;
    }

    /**
     * Builds a source from validated Calico config for the overworld dimension scope.
     *
     * @throws IllegalArgumentException if no valid biomes remain
     */
    public static WeightedBiomeSource fromConfig(CalicoWorldGenConfig config, long worldSeed,
            HolderGetter<Biome> biomes) {
        return fromConfig(config, worldSeed, biomes, BiomeDimension.OVERWORLD);
    }

    /**
     * Builds a source from validated Calico config, soft-dropping missing / wrong-dimension biomes.
     *
     * @param dimensionScope biomes that do not belong to this dimension are soft-dropped
     * @throws IllegalArgumentException if no valid biomes remain
     */
    public static WeightedBiomeSource fromConfig(CalicoWorldGenConfig config, long worldSeed,
            HolderGetter<Biome> biomes, BiomeDimension dimensionScope) {
        List<WeightedBiomeEntry> resolved = resolveEntries(config, biomes, dimensionScope, true);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "Calico: no selected biomes resolved from the registry for " + dimensionScope + ". "
                            + CalicoConfigValidation.MSG_EMPTY);
        }
        if (resolved.size() == 1) {
            Calico.LOGGER.debug("Calico: single-biome selection → 100% {}",
                    resolved.getFirst().biome().unwrapKey().map(ResourceKey::location).orElse(null));
        }
        return new WeightedBiomeSource(worldSeed, resolved);
    }

    /**
     * Resolves config entries to weighted holders for {@code dimensionScope}.
     * Soft-drops invalid ids, missing registry entries, and wrong-dimension biomes.
     * Returns empty list when nothing remains (does not throw).
     */
    public static List<WeightedBiomeEntry> resolveEntries(CalicoWorldGenConfig config,
            HolderGetter<Biome> biomes, BiomeDimension dimensionScope) {
        return resolveEntries(config, biomes, dimensionScope, false);
    }

    /**
     * @param failOnInvalidConfig when true, throws if config fails create validation
     */
    public static List<WeightedBiomeEntry> resolveEntries(CalicoWorldGenConfig config,
            HolderGetter<Biome> biomes, BiomeDimension dimensionScope, boolean failOnInvalidConfig) {
        CalicoConfigValidation.Result validation = CalicoConfigValidation.validateForCreate(config);
        if (!validation.valid()) {
            if (failOnInvalidConfig) {
                throw new IllegalArgumentException(validation.message());
            }
            return List.of();
        }
        CalicoWorldGenConfig sanitized = validation.sanitized();
        List<WeightedBiomeEntry> resolved = new ArrayList<>();
        for (SelectedBiomeEntry entry : sanitized.selectedBiomes()) {
            ResourceLocation id = entry.resourceLocationOrNull();
            if (id == null) {
                Calico.LOGGER.warn("Calico: soft-dropping invalid biome id '{}'", entry.id());
                continue;
            }
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
            Optional<Holder.Reference<Biome>> holder = biomes.get(key);
            if (holder.isEmpty()) {
                Calico.LOGGER.warn(
                        "Calico: soft-dropping missing biome '{}' (not in registry / climate unavailable)",
                        id);
                continue;
            }
            Holder<Biome> biomeHolder = holder.get();
            if (!BiomeRegistryDiscovery.matchesDimension(biomeHolder, dimensionScope)) {
                BiomeDimension actual = BiomeRegistryDiscovery.classifyDimension(biomeHolder);
                Calico.LOGGER.warn(
                        "Calico: soft-dropping biome '{}' — dimension {} does not match scope {}",
                        id, actual, dimensionScope);
                continue;
            }
            if (!Double.isFinite(entry.weight()) || entry.weight() <= 0.0d) {
                continue;
            }
            resolved.add(new WeightedBiomeEntry(biomeHolder, entry.weight()));
        }
        return List.copyOf(resolved);
    }

    /**
     * Like {@link #fromConfig} but returns empty when no biomes remain for the dimension
     * (for optional Nether/End stems).
     */
    public static Optional<WeightedBiomeSource> tryFromConfig(CalicoWorldGenConfig config, long worldSeed,
            HolderGetter<Biome> biomes, BiomeDimension dimensionScope) {
        List<WeightedBiomeEntry> resolved = resolveEntries(config, biomes, dimensionScope);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WeightedBiomeSource(worldSeed, resolved));
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(biomes);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        if (biomes.length == 1) {
            return biomes[0];
        }
        // Deterministic pick from world seed + quart coords (Y ignored for horizontal biomes).
        long hash = mixSeed(seed, quartX, quartZ);
        double unit = ((hash >>> 1) & 0x1FFFFFFFFFFFFFL) / (double) 0x1FFFFFFFFFFFFFL;
        double target = unit * totalWeight;
        for (int i = 0; i < cumulative.length; i++) {
            if (target < cumulative[i]) {
                return biomes[i];
            }
        }
        return biomes[biomes.length - 1];
    }

    /** Stable mix replacing deprecated {@code Mth.getSeed} for biome picking. */
    private static long mixSeed(long worldSeed, int quartX, int quartZ) {
        long h = worldSeed ^ ((long) quartX * 341873128712L) ^ ((long) quartZ * 132897987541L);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
