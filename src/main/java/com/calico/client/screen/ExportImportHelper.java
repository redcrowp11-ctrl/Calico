package com.calico.client.screen;

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
 * Clipboard JSON export/import using locked {@link CalicoWorldGenConfig#CODEC}.
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
