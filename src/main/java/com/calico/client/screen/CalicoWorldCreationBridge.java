package com.calico.client.screen;

import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.worldgen.CalicoCreateWorldBridge;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * UI-facing create handoff: validates selection, stores create-time pending config,
 * and updates {@link CreateWorldScreen} dimensions via {@link CalicoCreateWorldBridge}.
 */
@OnlyIn(Dist.CLIENT)
public final class CalicoWorldCreationBridge {
    private CalicoWorldCreationBridge() {
    }

    /**
     * @return true if dimensions were updated; false if validation blocked create
     */
    public static boolean submit(CreateWorldScreen createWorldScreen, CalicoWorldGenConfig config) {
        CalicoConfigValidation.Result validation = CalicoConfigValidation.validateForCreate(config);
        if (!validation.valid()) {
            return false;
        }
        CalicoWorldGenConfig sanitized = validation.sanitized();
        long seed = WorldOptions.parseSeed(createWorldScreen.getUiState().getSeed())
                .orElseGet(() -> createWorldScreen.getUiState().getSettings().options().seed());
        createWorldScreen.getUiState().updateDimensions(
                (registries, dimensions) -> CalicoCreateWorldBridge.apply(registries, dimensions, sanitized, seed));
        return true;
    }
}
