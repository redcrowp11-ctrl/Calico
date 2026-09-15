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
 * Done→Create bake contract (from 5772325): Customize Done writes pending + apply();
 * world-type select can reset LevelStem to datapack OVERWORLD; we re-bake on init,
 * on uiState change, and every client tick while Create World is open so noise
 * settings cannot stay OVERWORLD when pending terrainStyle is custom.
 * Banner/toast UX stripped — quiet INFO logs only.
 */
@Mod(value = Calico.MOD_ID, dist = Dist.CLIENT)
public class CalicoClient {
    private static boolean applyingCreateTimeConfig;
    private static CreateWorldScreen hookedScreen;

    /** Log-only Done confirm (no toast / no orange banner). */
    public static void showCreateConfirm(Component message) {
        if (message != null && !message.getString().isEmpty()) {
            Calico.LOGGER.info("Calico: {}", message.getString());
        }
    }

    public CalicoClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterPresetEditors);
        NeoForge.EVENT_BUS.addListener(CalicoClient::onScreenInit);
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
            TerrainStyle style = config.terrainStyle() == null ? TerrainStyle.NORMAL : config.terrainStyle();
            if (style.isCustomTerrain()
                    && baked instanceof NoiseBasedChunkGenerator ng
                    && ng.stable(NoiseGeneratorSettings.OVERWORLD)) {
                Calico.LOGGER.error(
                        "Calico: BAKE FAILED — pending terrainStyle={} but overworld stem still OVERWORLD after apply",
                        style.serializedName());
            }
        } catch (IllegalArgumentException ex) {
            Calico.LOGGER.warn("Calico: failed to apply create-time config to LevelStem: {}", ex.getMessage());
        } finally {
            applyingCreateTimeConfig = false;
        }
    }

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
     * Custom style + {@code stable(OVERWORLD)} always forces re-bake (5772325 heal).
     */
    static boolean needsBake(ChunkGenerator overworld, CalicoWorldGenConfig config) {
        if (!(overworld instanceof NoiseBasedChunkGenerator noiseGen)) {
            return true;
        }
        TerrainStyle style = config.terrainStyle() == null ? TerrainStyle.NORMAL : config.terrainStyle();
        if (style.isCustomTerrain() && noiseGen.stable(NoiseGeneratorSettings.OVERWORLD)) {
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
        if (style == TerrainStyle.ISLANDS && !noiseGen.stable(CalicoTerrainStyles.ISLANDS)) {
            return true;
        }
        if (style == TerrainStyle.BIG_ISLANDS && !noiseGen.stable(CalicoTerrainStyles.BIG_ISLANDS)) {
            return true;
        }
        if (style == TerrainStyle.ANT_HILL && !noiseGen.stable(CalicoTerrainStyles.ANT_HILL)) {
            return true;
        }
        if (style == TerrainStyle.MOUNTAINOUS && !noiseGen.stable(NoiseGeneratorSettings.AMPLIFIED)) {
            return true;
        }
        if (style == TerrainStyle.CAVE && !noiseGen.stable(NoiseGeneratorSettings.CAVES)) {
            return true;
        }
        if (!(noiseGen.getBiomeSource() instanceof WeightedBiomeSource)) {
            return true;
        }
        return false;
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
}
