package com.calico.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * Shared validation API for UI + generation.
 * Empty list OR all weights ≤ 0 / non-finite (NaN, Inf) → invalid (block create).
 */
public final class CalicoConfigValidation {
    public static final String MSG_EMPTY =
            "Select at least one biome with a positive weight to create a world.";
    public static final String MSG_UNSUPPORTED_VERSION =
            "Unsupported Calico config version.";
    public static final String MSG_INVALID_ID =
            "Biome id must be a full resource location (modid:biome).";
    public static final String MSG_NON_POSITIVE_WEIGHT =
            "Biome weight must be a finite number greater than 0 (non-positive / NaN / Inf weights are ignored).";

    private CalicoConfigValidation() {
    }

    public record Result(boolean valid, String message, List<String> warnings, CalicoWorldGenConfig sanitized) {
        public static Result ok(CalicoWorldGenConfig sanitized, List<String> warnings) {
            return new Result(true, "", List.copyOf(warnings), sanitized);
        }

        public static Result fail(String message, List<String> warnings, CalicoWorldGenConfig sanitized) {
            return new Result(false, message, List.copyOf(warnings), sanitized);
        }
    }

    /**
     * Validates config for world creation. Soft-ignores non-positive / non-finite weights with warnings.
     * Blocks create when no positive finite-weight biomes remain.
     */
    public static Result validateForCreate(CalicoWorldGenConfig config) {
        Objects.requireNonNull(config, "config");
        List<String> warnings = new ArrayList<>();

        if (config.version() != CalicoWorldGenConfig.PHASE1_VERSION) {
            return Result.fail(MSG_UNSUPPORTED_VERSION + " Got " + config.version() + ", expected "
                    + CalicoWorldGenConfig.PHASE1_VERSION + ".", warnings, config);
        }

        if (config.selectedBiomes() == null || config.selectedBiomes().isEmpty()) {
            return Result.fail(MSG_EMPTY, warnings, config.sanitized());
        }

        for (SelectedBiomeEntry entry : config.selectedBiomes()) {
            if (entry == null) {
                warnings.add("Skipped null selectedBiomes entry.");
                continue;
            }
            if (entry.id() == null || ResourceLocation.tryParse(entry.id()) == null) {
                warnings.add(MSG_INVALID_ID + " Got: " + entry.id());
                continue;
            }
            if (!Double.isFinite(entry.weight()) || entry.weight() <= 0.0d) {
                warnings.add(MSG_NON_POSITIVE_WEIGHT + " id=" + entry.id() + " weight=" + entry.weight());
            }
        }

        CalicoWorldGenConfig sanitized = config.sanitized();
        if (sanitized.selectedBiomes().isEmpty()) {
            return Result.fail(MSG_EMPTY, warnings, sanitized);
        }
        return Result.ok(sanitized, warnings);
    }

    /** UI-facing short message when create should stay disabled. */
    public static String createBlockedMessage(CalicoWorldGenConfig config) {
        Result result = validateForCreate(config);
        return result.valid() ? "" : result.message();
    }
}
