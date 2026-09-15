package com.calico.client;

import com.calico.Calico;
import com.calico.client.data.BiomeSelectionPersistence;
import com.calico.client.screen.CalicoCreateWorldScreen;
import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoCreateTimeConfig;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.config.TerrainStyle;
import com.calico.worldgen.CalicoCreateWorldBridge;
import com.calico.worldgen.CalicoWorldPresets;
import com.calico.worldgen.WeightedBiomeSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
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

    /** Shown on CreateWorldScreen after Customize Done — proves terrainStyle handoff. */
    private static Component createConfirmMessage = Component.empty();
    private static long createConfirmUntilMs;

    public static void showCreateConfirm(Component message) {
        createConfirmMessage = message == null ? Component.empty() : message;
        createConfirmUntilMs = System.currentTimeMillis() + 30_000L;
        Calico.LOGGER.info("Calico: {}", createConfirmMessage.getString());
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            SystemToast.addOrUpdate(
                    mc.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("Calico Terrain"),
                    createConfirmMessage);
        }
    }

    public CalicoClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterPresetEditors);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenRender);
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
     * When create-world is open on the Calico world type, ensure pending create-time config
     * (Customize Done, or last-selection JSON) is baked into LevelStem.
     * <p>
     * Root-cause fix for terrainStyle bake miss: world-type selection resets dimensions to the
     * datapack preset (plains + OVERWORLD). A fingerprint short-circuit previously skipped
     * re-bake after that reset, so Sky Islands + Done could still spawn vanilla plains.
     * Always re-apply when the overworld stem does not yet match the pending config.
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen createWorldScreen)) {
            return;
        }
        ensurePendingFromPersistence(createWorldScreen);
        createWorldScreen.getUiState().addListener(state -> {
            if (applyingCreateTimeConfig) {
                return;
            }
            Holder<WorldPreset> preset = state.getWorldType().preset();
            if (preset == null || !preset.is(CalicoWorldPresets.CALICO)) {
                return;
            }
            ensurePendingFromPersistence(createWorldScreen);
            if (!CalicoCreateWorldBridge.hasValidPending()) {
                return;
            }
            CalicoWorldGenConfig config = CalicoCreateTimeConfig.peekValidPending().orElse(null);
            if (config == null) {
                return;
            }
            if (!needsBake(state.getSettings().selectedDimensions().overworld(), config)) {
                return;
            }
            long seed = state.getSettings().options().seed();
            applyingCreateTimeConfig = true;
            try {
                state.updateDimensions((registries, dimensions) ->
                        CalicoWorldPresets.applyCreateTimeConfig(registries, dimensions, config, seed));
                Calico.LOGGER.info(
                        "Calico: auto-baked create-time LevelStem (terrainStyle={}, biomes={})",
                        config.terrainStyle() == null ? "normal" : config.terrainStyle().serializedName(),
                        config.selectedBiomes().size());
            } catch (IllegalArgumentException ex) {
                Calico.LOGGER.warn("Calico: failed to apply create-time config to LevelStem: {}", ex.getMessage());
            } finally {
                applyingCreateTimeConfig = false;
            }
        });
    }

    /**
     * Session restart clears in-memory pending; last Customize selection still lives on disk.
     * Hydrate pending so Calico world-type select cannot ignore terrainStyle.
     */
    private static void ensurePendingFromPersistence(CreateWorldScreen screen) {
        if (CalicoCreateWorldBridge.hasValidPending()) {
            return;
        }
        Minecraft minecraft = screen.getMinecraft();
        if (minecraft == null) {
            return;
        }
        BiomeSelectionPersistence.load(minecraft).ifPresent(loaded -> {
            CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(loaded);
            if (result.valid()) {
                CalicoCreateTimeConfig.setPending(result.sanitized());
                Calico.LOGGER.info(
                        "Calico: restored create-time pending from last selection (terrainStyle={})",
                        result.sanitized().terrainStyle().serializedName());
            }
        });
    }

    /**
     * Returns true when overworld stem still looks like the datapack placeholder (or otherwise
     * does not reflect pending biomes / terrainStyle) — create path must not ignore style.
     */
    private static boolean needsBake(ChunkGenerator overworld, CalicoWorldGenConfig config) {
        if (!(overworld instanceof NoiseBasedChunkGenerator noiseGen)) {
            return true;
        }
        TerrainStyle style = config.terrainStyle() == null ? TerrainStyle.NORMAL : config.terrainStyle();
        boolean customTerrain = style != TerrainStyle.NORMAL
                && style != TerrainStyle.ISLANDS
                && style != TerrainStyle.BIG_ISLANDS
                && style != TerrainStyle.MOUNTAINOUS
                && style != TerrainStyle.CAVE;
        // Datapack preset / world-type reset leaves vanilla OVERWORLD noise — that is the bake miss.
        if (customTerrain && noiseGen.stable(NoiseGeneratorSettings.OVERWORLD)) {
            return true;
        }
        if (style == TerrainStyle.SKY_ISLANDS && !noiseGen.stable(com.calico.worldgen.CalicoTerrainStyles.SKY_ISLANDS)) {
            return true;
        }
        if (style == TerrainStyle.WEDDING_CAKE && !noiseGen.stable(com.calico.worldgen.CalicoTerrainStyles.WEDDING_CAKE)) {
            return true;
        }
        if (!(noiseGen.getBiomeSource() instanceof WeightedBiomeSource)) {
            return true;
        }
        return false;
    }

    /** Draws Done confirm banner on create-world so terrainStyle handoff is visible. */
    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }
        if (createConfirmMessage.getString().isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() > createConfirmUntilMs) {
            createConfirmMessage = Component.empty();
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();
        int barH = 42;
        graphics.fill(0, 0, screen.width, barH, 0xE0AA2200);
        graphics.fill(0, barH, screen.width, barH + 2, 0xFFFFFFFF);
        Component title = Component.literal("CALICO TERRAIN APPLIED — READ THIS");
        graphics.drawCenteredString(mc.font, title, screen.width / 2, 6, 0xFFFFFF);
        graphics.drawCenteredString(mc.font, createConfirmMessage, screen.width / 2, 20, 0x88FF88);
        graphics.drawCenteredString(mc.font,
                Component.literal("Top of Create World screen (also a toast). Then click Create."),
                screen.width / 2, 32, 0xFFEE88);
    }
}
