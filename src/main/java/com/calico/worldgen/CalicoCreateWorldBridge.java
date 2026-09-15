package com.calico.worldgen;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import com.calico.Calico;
import com.calico.config.CalicoConfigFingerprint;
import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoCreateTimeConfig;
import com.calico.config.CalicoWorldGenConfig;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldDimensions;

/**
 * Create-world handoff: takes player {@link CalicoWorldGenConfig} and bakes it into
 * {@link WorldDimensions} / {@link net.minecraft.world.level.dimension.LevelStem}
 * biome sources so generation actually uses the selection (not the datapack placeholder).
 */
public final class CalicoCreateWorldBridge {
    private CalicoCreateWorldBridge() {
    }

    /**
     * Validates, stores as create-time pending, and returns a dimensions updater that
     * applies the sanitized config into LevelStem biome sources.
     *
     * @throws IllegalArgumentException if config is invalid for create
     */
    public static BiFunction<RegistryAccess, WorldDimensions, WorldDimensions> submit(
            CalicoWorldGenConfig config, long worldSeed) {
        Objects.requireNonNull(config, "config");
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(config);
        if (!result.valid()) {
            throw new IllegalArgumentException(result.message());
        }
        CalicoWorldGenConfig sanitized = result.sanitized();
        CalicoCreateTimeConfig.setPending(sanitized);
        Calico.LOGGER.info("Calico: create-time config submitted ({} biomes)", sanitized.selectedBiomes().size());
        Calico.LOGGER.info("Calico: determinism stamp {}", CalicoConfigFingerprint.stamp(worldSeed, sanitized));
        return (registries, dimensions) ->
                CalicoWorldPresets.applyCreateTimeConfig(registries, dimensions, sanitized, worldSeed);
    }

    /**
     * Applies currently pending valid create-time config into dimensions, if any.
     *
     * @return updated dimensions, or the input unchanged when nothing pending
     */
    public static WorldDimensions applyPending(RegistryAccess registries, WorldDimensions dimensions,
            long worldSeed) {
        return CalicoWorldPresets.applyPendingCreateTimeConfig(registries, dimensions, worldSeed);
    }

    /**
     * Applies {@code config} into dimensions without requiring a prior {@link #submit}.
     * Also updates the pending holder so Customize / re-entry stays consistent.
     */
    public static WorldDimensions apply(RegistryAccess registries, WorldDimensions dimensions,
            CalicoWorldGenConfig config, long worldSeed) {
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(config);
        if (!result.valid()) {
            throw new IllegalArgumentException(result.message());
        }
        CalicoWorldGenConfig sanitized = result.sanitized();
        CalicoCreateTimeConfig.setPending(sanitized);
        return CalicoWorldPresets.applyCreateTimeConfig(registries, dimensions, sanitized, worldSeed);
    }

    /** @return whether a valid create-time config is pending */
    public static boolean hasValidPending() {
        return CalicoCreateTimeConfig.peekValidPending().isPresent();
    }

    public static Optional<CalicoWorldGenConfig> peekPending() {
        return CalicoCreateTimeConfig.peekValidPending();
    }
}
