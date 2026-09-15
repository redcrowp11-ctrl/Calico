package com.calico.client;

import com.calico.Calico;
import com.calico.client.data.BiomeSelectionPersistence;
import com.calico.client.screen.CalicoCreateWorldScreen;
import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoCreateTimeConfig;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.config.TerrainStyle;
import com.calico.worldgen.CalicoCreateWorldBridge;
import com.calico.worldgen.CalicoTerrainStyles;
import com.calico.worldgen.CalicoWorldPresets;
import com.calico.worldgen.WeightedBiomeSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side entry: registers the Calico create-world biome picker and bakes
 * pending create-time config into LevelStem when the Calico world type is selected.
 * <p>
 * Done→Create bake contract: Customize Done writes pending + apply(); world-type
 * select can reset LevelStem to datapack OVERWORLD; we re-bake on init, on uiState
 * change, and every client tick while Create World is open so noise settings cannot
 * stay OVERWORLD when pending terrainStyle is sky_islands / wedding_cake.
 */
@Mod(value = Calico.MOD_ID, dist = Dist.CLIENT)
public class CalicoClient {
    /** Guard against recursive uiState listener updates when applying LevelStem. */
    private static boolean applyingCreateTimeConfig;

    /** Avoid stacking duplicate uiState listeners on the same CreateWorldScreen instance. */
    private static CreateWorldScreen hookedScreen;

    /** Shown on CreateWorldScreen after Customize Done — proves terrainStyle handoff. */
    private static Component createConfirmMessage = Component.empty();
    private static long createConfirmUntilMs;

    public static void showCreateConfirm(Component message) {
        createConfirmMessage = message == null ? Component.empty() : message;
        // Quiet confirm: short toast + slim chip (debug scream banner retired).
        createConfirmUntilMs = System.currentTimeMillis() + 8_000L;
        Calico.LOGGER.info("Calico: {}", createConfirmMessage.getString());
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            SystemToast.addOrUpdate(
                    mc.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("Calico"),
                    createConfirmMessage);
        }
    }

    public CalicoClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterPresetEditors);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenRender);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onClientTick);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Calico.LOGGER.debug("Calico client setup");
    }

    private void onRegisterPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(CalicoWorldPresets.CALICO, CalicoCreateWorldScreen::create);
        Calico.LOGGER.info("Calico: registered create-world biome picker for preset {}",
                CalicoWorldPresets.CALICO.location());
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen createWorldScreen)) {
            return;
        }
        ensurePendingFromPersistence(createWorldScreen);
        tryBake(createWorldScreen, "screen-init");

        if (hookedScreen == createWorldScreen) {
            return;
        }
        hookedScreen = createWorldScreen;
        createWorldScreen.getUiState().addListener(state -> {
            if (applyingCreateTimeConfig) {
                return;
            }
            Holder<WorldPreset> preset = state.getWorldType().preset();
            if (preset == null || !preset.is(CalicoWorldPresets.CALICO)) {
                return;
            }
            tryBake(createWorldScreen, "ui-listener");
        });
    }

    /**
     * Continuous heal: world-type / datapack reset can wipe noise settings after Done.
     * Keep overworld stem matched to pending terrainStyle until the player leaves Create World.
     */
    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !(mc.screen instanceof CreateWorldScreen createWorldScreen)) {
            return;
        }
        Holder<WorldPreset> preset = createWorldScreen.getUiState().getWorldType().preset();
        if (preset == null || !preset.is(CalicoWorldPresets.CALICO)) {
            return;
        }
        tryBake(createWorldScreen, "client-tick");
    }

    private static void tryBake(CreateWorldScreen createWorldScreen, String reason) {
        if (applyingCreateTimeConfig) {
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
        Holder<WorldPreset> preset = createWorldScreen.getUiState().getWorldType().preset();
        if (preset == null || !preset.is(CalicoWorldPresets.CALICO)) {
            return;
        }
        ChunkGenerator overworld = createWorldScreen.getUiState().getSettings().selectedDimensions().overworld();
        if (!needsBake(overworld, config)) {
            return;
        }
        long seed = createWorldScreen.getUiState().getSettings().options().seed();
        applyingCreateTimeConfig = true;
        try {
            createWorldScreen.getUiState().updateDimensions((registries, dimensions) ->
                    CalicoWorldPresets.applyCreateTimeConfig(registries, dimensions, config, seed));
            ChunkGenerator baked = createWorldScreen.getUiState().getSettings().selectedDimensions().overworld();
            String noiseKey = describeNoiseSettings(baked);
            Calico.LOGGER.info(
                    "Calico: auto-baked LevelStem via {} (terrainStyle={}, biomes={}, noiseSettings={})",
                    reason,
                    config.terrainStyle() == null ? "normal" : config.terrainStyle().serializedName(),
                    config.selectedBiomes().size(),
                    noiseKey);
            if (isCustomTerrain(config.terrainStyle())
                    && baked instanceof NoiseBasedChunkGenerator ng
                    && ng.stable(NoiseGeneratorSettings.OVERWORLD)) {
                Calico.LOGGER.error(
                        "Calico: BAKE FAILED — pending terrainStyle={} but overworld stem still OVERWORLD after apply",
                        config.terrainStyle().serializedName());
            }
        } catch (IllegalArgumentException ex) {
            Calico.LOGGER.warn("Calico: failed to apply create-time config to LevelStem: {}", ex.getMessage());
        } finally {
            applyingCreateTimeConfig = false;
        }
    }

    /**
     * Does NOT overwrite an already-valid in-memory pending (Done always wins over disk).
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
     * Returns true when overworld stem does not yet reflect pending biomes / terrainStyle.
     * <p>
     * <b>PROOF — needsBake cannot skip sky/cake while stem is still OVERWORLD:</b>
     * when pending style is {@link TerrainStyle#SKY_ISLANDS} or {@link TerrainStyle#WEDDING_CAKE}
     * and {@code noiseGen.stable(OVERWORLD)} is true, the customTerrain branch returns
     * {@code true}. Style-specific branches also return {@code true} unless the stem is
     * {@code stable(calico:sky_islands)} / {@code stable(calico:wedding_cake)} (or a clear
     * Holder.direct heuristic). A datapack/world-type reset that leaves OVERWORLD always
     * forces re-bake.
     */
    static boolean needsBake(ChunkGenerator overworld, CalicoWorldGenConfig config) {
        if (!(overworld instanceof NoiseBasedChunkGenerator noiseGen)) {
            return true;
        }
        TerrainStyle style = config.terrainStyle() == null ? TerrainStyle.NORMAL : config.terrainStyle();
        // PROOF: custom style + stable(OVERWORLD) ⇒ true (cannot skip).
        if (isCustomTerrain(style) && noiseGen.stable(NoiseGeneratorSettings.OVERWORLD)) {
            return true;
        }
        if (style == TerrainStyle.SKY_ISLANDS
                && !noiseGen.stable(CalicoTerrainStyles.SKY_ISLANDS)
                && !looksLikeSkyIslands(noiseGen)) {
            return true;
        }
        if (style == TerrainStyle.WEDDING_CAKE
                && !noiseGen.stable(CalicoTerrainStyles.WEDDING_CAKE)
                && !looksLikeWeddingCake(noiseGen)) {
            return true;
        }
        if (!(noiseGen.getBiomeSource() instanceof WeightedBiomeSource)) {
            return true;
        }
        return false;
    }

    private static boolean isCustomTerrain(TerrainStyle style) {
        return style == TerrainStyle.SKY_ISLANDS || style == TerrainStyle.WEDDING_CAKE;
    }

    private static boolean looksLikeSkyIslands(NoiseBasedChunkGenerator noiseGen) {
        NoiseGeneratorSettings s = noiseGen.generatorSettings().value();
        return s.seaLevel() < 0 && !s.isAquifersEnabled() && s.noiseSettings().minY() >= 0
                && !noiseGen.stable(NoiseGeneratorSettings.OVERWORLD);
    }

    private static boolean looksLikeWeddingCake(NoiseBasedChunkGenerator noiseGen) {
        NoiseGeneratorSettings s = noiseGen.generatorSettings().value();
        return s.seaLevel() < 0 && !s.isAquifersEnabled() && s.noiseSettings().minY() < 0
                && !noiseGen.stable(NoiseGeneratorSettings.OVERWORLD);
    }

    private static String describeNoiseSettings(ChunkGenerator overworld) {
        if (!(overworld instanceof NoiseBasedChunkGenerator noiseGen)) {
            return overworld.getClass().getSimpleName();
        }
        Holder<NoiseGeneratorSettings> holder = noiseGen.generatorSettings();
        return holder.unwrapKey()
                .map(ResourceKey::location)
                .map(Object::toString)
                .orElseGet(() -> "direct(seaLevel=" + holder.value().seaLevel()
                        + ",aquifers=" + holder.value().isAquifersEnabled()
                        + ",minY=" + holder.value().noiseSettings().minY()
                        + ",height=" + holder.value().noiseSettings().height() + ")");
    }

    /** Slim Done confirm chip on create-world (toast carries the same text). */
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
        int pad = 6;
        int textW = mc.font.width(createConfirmMessage);
        int barH = 16;
        int x0 = Math.max(8, (screen.width - textW) / 2 - pad);
        int x1 = Math.min(screen.width - 8, x0 + textW + pad * 2);
        graphics.fill(x0, 4, x1, 4 + barH, 0xC0222222);
        graphics.drawCenteredString(mc.font, createConfirmMessage, screen.width / 2, 8, 0xAADD88);
    }
}
