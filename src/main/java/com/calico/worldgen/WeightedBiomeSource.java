package com.calico.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.calico.Calico;
import com.calico.config.BiomeScale;
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
 *   <li>Deterministic from world {@code seed} + position</li>
 *   <li>Single biome ⇒ always that biome (100%)</li>
 *   <li>{@link BiomeScale#NORMAL} — large contiguous Voronoi regions (vanilla-comparable) with light border dither</li>
 *   <li>{@link BiomeScale#QUILT} — per-quart hash → tight patchwork micro-biomes</li>
 * </ul>
 */
public class WeightedBiomeSource extends BiomeSource {
    /**
     * Quart-space Voronoi cell size for {@link BiomeScale#NORMAL}.
     * 128 quarte × 4 blocks = 512-block mean region scale (vanilla-comparable).
     */
    private static final int NORMAL_CELL_QUARTS = 128;

    /**
     * Soft border blend width in quart space for Normal (Voronoi) scale.
     * 6 quarts × 4 blocks ≈ 24-block subtle dither band — not quilt.
     */
    private static final double NORMAL_BORDER_BLEND_QUARTS = 6.0;

    public static final MapCodec<WeightedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            com.mojang.serialization.Codec.LONG.fieldOf("seed").forGetter(WeightedBiomeSource::seed),
            WeightedBiomeEntry.CODEC.listOf().fieldOf("biomes").forGetter(WeightedBiomeSource::entries),
            BiomeScale.CODEC.optionalFieldOf("biomeScale", BiomeScale.NORMAL).forGetter(WeightedBiomeSource::biomeScale)
    ).apply(instance, WeightedBiomeSource::new));

    private final long seed;
    private final List<WeightedBiomeEntry> entries;
    private final BiomeScale biomeScale;
    private final double totalWeight;
    private final Holder<Biome>[] biomes;
    private final double[] cumulative;

    /** Defaults to {@link BiomeScale#NORMAL}. */
    public WeightedBiomeSource(long seed, List<WeightedBiomeEntry> entries) {
        this(seed, entries, BiomeScale.NORMAL);
    }

    @SuppressWarnings("unchecked")
    public WeightedBiomeSource(long seed, List<WeightedBiomeEntry> entries, BiomeScale biomeScale) {
        this.seed = seed;
        this.biomeScale = biomeScale == null ? BiomeScale.NORMAL : biomeScale;
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

    public BiomeScale biomeScale() {
        return biomeScale;
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
        BiomeScale scale = config.biomeScale() == null ? BiomeScale.NORMAL : config.biomeScale();
        return new WeightedBiomeSource(worldSeed, resolved, scale);
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
        BiomeScale scale = config.biomeScale() == null ? BiomeScale.NORMAL : config.biomeScale();
        return Optional.of(new WeightedBiomeSource(worldSeed, resolved, scale));
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
        if (biomeScale.isQuilt()) {
            // High-frequency: independent pick per quart → tight quilt / micro-biomes.
            return pickFromHash(mixSeed(seed, quartX, quartZ));
        }
        // Normal: large Voronoi cells → contiguous vanilla-scale regions; light border dither.
        return pickNormalVoronoi(quartX, quartZ);
    }

    /**
     * Nearest jittered Voronoi cell in quart space, with a subtle soft blend at borders.
     * Deep inside a cell the biome is stable; near edges a small dither band softens
     * desert/plains-style hard seams without becoming quilt.
     */
    private Holder<Biome> pickNormalVoronoi(int quartX, int quartZ) {
        final int cell = NORMAL_CELL_QUARTS;
        int cellX = Math.floorDiv(quartX, cell);
        int cellZ = Math.floorDiv(quartZ, cell);

        double bestDist = Double.POSITIVE_INFINITY;
        double secondDist = Double.POSITIVE_INFINITY;
        int bestCX = cellX;
        int bestCZ = cellZ;
        int secondCX = cellX;
        int secondCZ = cellZ;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                long h = mixSeed(seed, cx, cz);
                // Jitter center within the cell for organic (non-grid) borders.
                double jx = ((h & 0xFFFFL) / 65535.0d) * cell;
                double jz = (((h >>> 16) & 0xFFFFL) / 65535.0d) * cell;
                double px = cx * (double) cell + jx;
                double pz = cz * (double) cell + jz;
                double ddx = quartX - px;
                double ddz = quartZ - pz;
                double dist = ddx * ddx + ddz * ddz;
                if (dist < bestDist) {
                    secondDist = bestDist;
                    secondCX = bestCX;
                    secondCZ = bestCZ;
                    bestDist = dist;
                    bestCX = cx;
                    bestCZ = cz;
                } else if (dist < secondDist) {
                    secondDist = dist;
                    secondCX = cx;
                    secondCZ = cz;
                }
            }
        }

        int pickCX = bestCX;
        int pickCZ = bestCZ;
        // Soft edge: when F2−F1 is within the blend band, occasionally use the second cell.
        if (secondDist < Double.POSITIVE_INFINITY && NORMAL_BORDER_BLEND_QUARTS > 0.0d) {
            double gap = Math.sqrt(secondDist) - Math.sqrt(bestDist);
            if (gap < NORMAL_BORDER_BLEND_QUARTS) {
                long edgeHash = mixSeed(seed ^ 0xD1B54A32D192ED03L, quartX, quartZ);
                double unit = ((edgeHash >>> 1) & 0x1FFFFFFFFFFFFFL) / (double) 0x1FFFFFFFFFFFFFL;
                // ~50% second-cell at exact border (gap=0), fading to 0% at blend edge — subtle, not quilt.
                double keepNearestBelow = 0.5d + 0.5d * (gap / NORMAL_BORDER_BLEND_QUARTS);
                if (unit > keepNearestBelow) {
                    pickCX = secondCX;
                    pickCZ = secondCZ;
                }
            }
        }

        // Distinct mix from jitter seed so biome pick ≠ jitter entropy alone.
        return pickFromHash(mixSeed(seed ^ 0x9E3779B97F4A7C15L, pickCX, pickCZ));
    }

    private Holder<Biome> pickFromHash(long hash) {
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
