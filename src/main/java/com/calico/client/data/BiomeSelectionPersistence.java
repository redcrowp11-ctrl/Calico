package com.calico.client.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;

import com.calico.Calico;
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
 * Persists last create config under the game dir using the locked JSON schema
 * ({@code version}, {@code selectedBiomes}, {@code biomeScale}, {@code terrainStyle}).
 * File: {@code config/calico-last-selection.json}
 */
@OnlyIn(Dist.CLIENT)
public final class BiomeSelectionPersistence {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String RELATIVE_PATH = "config/calico-last-selection.json";

    private BiomeSelectionPersistence() {
    }

    public static Path path(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve(RELATIVE_PATH);
    }

    public static Optional<CalicoWorldGenConfig> load(Minecraft minecraft) {
        Path file = path(minecraft);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return CalicoWorldGenConfig.CODEC.parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(msg -> LOGGER.warn("Calico: failed to parse last selection: {}", msg));
        } catch (Exception ex) {
            LOGGER.warn("Calico: could not load last selection from {}", file, ex);
            return Optional.empty();
        }
    }

    public static void save(Minecraft minecraft, CalicoWorldGenConfig config) {
        Path file = path(minecraft);
        try {
            Files.createDirectories(file.getParent());
            JsonElement element = CalicoWorldGenConfig.CODEC.encodeStart(JsonOps.INSTANCE, config.sanitized())
                    .getOrThrow();
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(element, writer);
            }
            Calico.LOGGER.debug("Calico: saved last selection to {}", file);
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Calico: could not save last selection to {}", file, ex);
        }
    }
}
