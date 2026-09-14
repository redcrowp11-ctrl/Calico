package com.calico.client;

import java.util.Objects;

import com.calico.Calico;
import com.calico.client.screen.CalicoCreateWorldScreen;
import com.calico.config.CalicoCreateTimeConfig;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.worldgen.CalicoCreateWorldBridge;
import com.calico.worldgen.CalicoWorldPresets;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side entry: registers the Calico create-world biome picker and bakes
 * pending create-time config into LevelStem when the Calico world type is selected.
 * <p>
 * Hook: NeoForge {@link RegisterPresetEditorsEvent} — no mixin required.
 */
@Mod(value = Calico.MOD_ID, dist = Dist.CLIENT)
public class CalicoClient {
    /** Guard against recursive uiState listener updates when applying LevelStem. */
    private static boolean applyingCreateTimeConfig;
    /** Fingerprint of last applied pending config + seed to skip redundant rebakes. */
    private static int lastAppliedFingerprint;

    public CalicoClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterPresetEditors);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenInit);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Calico.LOGGER.debug("Calico client setup");
    }

    /**
     * Opens {@link CalicoCreateWorldScreen} when the player clicks Customize on the
     * {@code calico:calico} world preset in create-world.
     */
    private void onRegisterPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(CalicoWorldPresets.CALICO, CalicoCreateWorldScreen::create);
        Calico.LOGGER.info("Calico: registered create-world biome picker for preset {}",
                CalicoWorldPresets.CALICO.location());
    }

    /**
     * When create-world is open on the Calico world type and a valid pending config exists,
     * bake it into LevelStem automatically (Customize Done / bridge submit → playable world).
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen createWorldScreen)) {
            return;
        }
        createWorldScreen.getUiState().addListener(state -> {
            if (applyingCreateTimeConfig) {
                return;
            }
            Holder<WorldPreset> preset = state.getWorldType().preset();
            if (preset == null || !preset.is(CalicoWorldPresets.CALICO)) {
                return;
            }
            if (!CalicoCreateWorldBridge.hasValidPending()) {
                return;
            }
            CalicoWorldGenConfig config = CalicoCreateTimeConfig.peekValidPending().orElse(null);
            if (config == null) {
                return;
            }
            long seed = state.getSettings().options().seed();
            int fingerprint = Objects.hash(config, seed);
            if (fingerprint == lastAppliedFingerprint) {
                return;
            }
            applyingCreateTimeConfig = true;
            try {
                state.updateDimensions((registries, dimensions) ->
                        CalicoWorldPresets.applyCreateTimeConfig(registries, dimensions, config, seed));
                lastAppliedFingerprint = fingerprint;
            } catch (IllegalArgumentException ex) {
                Calico.LOGGER.warn("Calico: failed to apply create-time config to LevelStem: {}", ex.getMessage());
            } finally {
                applyingCreateTimeConfig = false;
            }
        });
    }
}
