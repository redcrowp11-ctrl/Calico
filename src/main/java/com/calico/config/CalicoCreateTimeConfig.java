package com.calico.config;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the create-time biome selection that must be baked into {@code LevelStem}
 * when a Calico world is created.
 * <p>
 * UI / create bridge sets the pending config before world creation; worldgen
 * helpers read it to rebuild the overworld (and scoped) biome source. Cleared
 * after a successful apply or explicitly by the caller.
 */
public final class CalicoCreateTimeConfig {
    private static final AtomicReference<CalicoWorldGenConfig> PENDING = new AtomicReference<>();

    private CalicoCreateTimeConfig() {
    }

    /** Sets the create-time selection (sanitized copy preferred by callers). */
    public static void setPending(CalicoWorldGenConfig config) {
        PENDING.set(config);
    }

    /** Clears any pending create-time selection. */
    public static void clearPending() {
        PENDING.set(null);
    }

    /** Peek without clearing. */
    public static Optional<CalicoWorldGenConfig> peekPending() {
        return Optional.ofNullable(PENDING.get());
    }

    /**
     * Returns pending config if present and valid for create, otherwise empty.
     * Does not clear.
     */
    public static Optional<CalicoWorldGenConfig> peekValidPending() {
        CalicoWorldGenConfig pending = PENDING.get();
        if (pending == null) {
            return Optional.empty();
        }
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(pending);
        return result.valid() ? Optional.of(result.sanitized()) : Optional.empty();
    }

    /**
     * Takes (clears) the pending config if valid for create.
     *
     * @return sanitized config, or empty if none / invalid
     */
    public static Optional<CalicoWorldGenConfig> takeValidPending() {
        CalicoWorldGenConfig pending = PENDING.getAndSet(null);
        if (pending == null) {
            return Optional.empty();
        }
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(pending);
        if (!result.valid()) {
            return Optional.empty();
        }
        return Optional.of(result.sanitized());
    }
}
