package com.calico.client.screen;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;

import com.calico.config.CalicoWorldGenConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Clipboard + file JSON export/import using locked {@link CalicoWorldGenConfig#CODEC}.
 * <p>
 * Round-trips the full create config: {@code version}, {@code selectedBiomes[{id,weight}]},
 * {@code biomeScale}, {@code terrainStyle} (CONFIG.md schema).
 */
@OnlyIn(Dist.CLIENT)
public final class ExportImportHelper {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ExportImportHelper() {
    }

    public static String toPrettyJson(CalicoWorldGenConfig config) {
        JsonElement element = CalicoWorldGenConfig.CODEC.encodeStart(JsonOps.INSTANCE, config.sanitized())
                .getOrThrow();
        return GSON.toJson(element);
    }

    public static void exportToClipboard(Minecraft minecraft, CalicoWorldGenConfig config) {
        minecraft.keyboardHandler.setClipboard(toPrettyJson(config));
    }

    public static Optional<CalicoWorldGenConfig> importFromClipboard(Minecraft minecraft) {
        String text = minecraft.keyboardHandler.getClipboard();
        return parse(text);
    }

    /**
     * Writes the full create config JSON to {@code path} (creates parent dirs).
     * Compatible with {@link com.calico.client.data.BiomeSelectionPersistence} schema.
     */
    public static void writeToFile(Path path, CalicoWorldGenConfig config) throws IOException {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        JsonElement element = CalicoWorldGenConfig.CODEC.encodeStart(JsonOps.INSTANCE, config.sanitized())
                .getOrThrow();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(element, writer);
        }
    }

    /** Reads a full create config JSON file (version/biomes/biomeScale/terrainStyle). */
    public static Optional<CalicoWorldGenConfig> readFromFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return CalicoWorldGenConfig.CODEC.parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(msg -> LOGGER.warn("Calico: preset file JSON invalid: {}", msg));
        } catch (Exception ex) {
            LOGGER.warn("Calico: could not read preset file {}", path, ex);
            return Optional.empty();
        }
    }

    public static Optional<CalicoWorldGenConfig> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            return CalicoWorldGenConfig.CODEC.parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(msg -> LOGGER.warn("Calico: import JSON invalid: {}", msg));
        } catch (Exception ex) {
            LOGGER.warn("Calico: import JSON parse failed", ex);
            return Optional.empty();
        }
    }
}
