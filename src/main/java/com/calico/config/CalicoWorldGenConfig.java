package com.calico.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * Locked Phase 1 world-gen selection config.
 * <pre>
 * {
 *   "version": 1,
 *   "selectedBiomes": [{ "id": "minecraft:desert", "weight": 1.0 }]
 * }
 * </pre>
 * Field names {@code version} and {@code selectedBiomes} are LOCKED.
 */
public record CalicoWorldGenConfig(int version, List<SelectedBiomeEntry> selectedBiomes) {
    public static final int PHASE1_VERSION = 1;

    public static final Codec<CalicoWorldGenConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("version").forGetter(CalicoWorldGenConfig::version),
            SelectedBiomeEntry.CODEC.listOf().fieldOf("selectedBiomes").forGetter(CalicoWorldGenConfig::selectedBiomes)
    ).apply(instance, CalicoWorldGenConfig::new));

    public static CalicoWorldGenConfig empty() {
        return new CalicoWorldGenConfig(PHASE1_VERSION, List.of());
    }

    public static CalicoWorldGenConfig of(List<SelectedBiomeEntry> biomes) {
        return new CalicoWorldGenConfig(PHASE1_VERSION, List.copyOf(biomes));
    }

    /**
     * Returns a config with non-positive weights dropped and duplicate ids resolved (last wins).
     * Does not validate emptiness — use {@link CalicoConfigValidation}.
     */
    public CalicoWorldGenConfig sanitized() {
        Map<String, Double> merged = new LinkedHashMap<>();
        for (SelectedBiomeEntry entry : selectedBiomes) {
            if (entry == null || entry.id() == null || entry.id().isBlank()) {
                continue;
            }
            if (entry.weight() <= 0.0d) {
                continue;
            }
            if (ResourceLocation.tryParse(entry.id()) == null) {
                continue;
            }
            merged.put(entry.id(), entry.weight());
        }
        List<SelectedBiomeEntry> out = new ArrayList<>(merged.size());
        merged.forEach((id, weight) -> out.add(new SelectedBiomeEntry(id, weight)));
        return new CalicoWorldGenConfig(version, List.copyOf(out));
    }
}
