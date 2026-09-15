package com.calico.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Stable fingerprint of a create-time {@link CalicoWorldGenConfig} for bug-report
 * determinism stamps (seed + config hash).
 * <p>
 * Fingerprint covers sanitized biomes (id + weight), {@code biomeScale}, and
 * {@code terrainStyle} — matching the locked CONFIG.md schema fields that affect gen.
 */
public final class CalicoConfigFingerprint {
    private CalicoConfigFingerprint() {
    }

    /**
     * Returns a short hex fingerprint (16 chars) of the sanitized config payload.
     * Same biomes/weights/scale/style ⇒ same fingerprint regardless of call site.
     */
    public static String fingerprint(CalicoWorldGenConfig config) {
        CalicoWorldGenConfig sanitized = config == null ? CalicoWorldGenConfig.empty() : config.sanitized();
        StringBuilder sb = new StringBuilder(128);
        sb.append("v").append(sanitized.version());
        sb.append("|scale=").append(
                sanitized.biomeScale() == null ? BiomeScale.NORMAL.serializedName() : sanitized.biomeScale().serializedName());
        sb.append("|style=").append(
                sanitized.terrainStyle() == null ? TerrainStyle.NORMAL.serializedName() : sanitized.terrainStyle().serializedName());
        for (SelectedBiomeEntry entry : sanitized.selectedBiomes()) {
            sb.append('|').append(entry.id()).append('@').append(formatWeight(entry.weight()));
        }
        return sha256Hex(sb.toString()).substring(0, 16);
    }

    /**
     * One-line stamp for logs: {@code seed=… config=… (style=…, scale=…, biomes=…)}.
     */
    public static String stamp(long worldSeed, CalicoWorldGenConfig config) {
        CalicoWorldGenConfig sanitized = config == null ? CalicoWorldGenConfig.empty() : config.sanitized();
        String style = sanitized.terrainStyle() == null
                ? TerrainStyle.NORMAL.serializedName()
                : sanitized.terrainStyle().serializedName();
        String scale = sanitized.biomeScale() == null
                ? BiomeScale.NORMAL.serializedName()
                : sanitized.biomeScale().serializedName();
        return String.format(
                Locale.ROOT,
                "seed=%d config=%s (style=%s, scale=%s, biomes=%d)",
                worldSeed,
                fingerprint(sanitized),
                style,
                scale,
                sanitized.selectedBiomes().size());
    }

    private static String formatWeight(double weight) {
        // Trim trailing zeros so 1.0 and 1.00 match; Locale.ROOT for stability.
        String s = String.format(Locale.ROOT, "%.6f", weight);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required on every JVM Calico targets; fall back to hashCode hex.
            return String.format(Locale.ROOT, "%016x", (long) payload.hashCode() & 0xffffffffL);
        }
    }
}
