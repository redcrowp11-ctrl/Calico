package com.calico.client.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.calico.config.BiomeScale;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.config.TerrainStyle;
import com.calico.config.SelectedBiomeEntry;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Mutable UI selection: biome id → weight, focused id, unavailable prune notes.
 */
@OnlyIn(Dist.CLIENT)
public final class BiomeSelectionState {
    private final LinkedHashMap<ResourceLocation, Double> selected = new LinkedHashMap<>();
    @Nullable
    private ResourceLocation focused;
    private BiomeScale biomeScale = BiomeScale.NORMAL;
    private TerrainStyle terrainStyle = TerrainStyle.NORMAL;
    private int lastUnavailableCount;
    private List<ResourceLocation> lastUnavailableIds = List.of();

    public Map<ResourceLocation, Double> selectedView() {
        return Collections.unmodifiableMap(selected);
    }

    public boolean isSelected(ResourceLocation id) {
        return selected.containsKey(id);
    }

    public int selectedCount() {
        return selected.size();
    }

    @Nullable
    public ResourceLocation focused() {
        return focused;
    }

    public void setFocused(@Nullable ResourceLocation focused) {
        this.focused = focused;
    }

    public BiomeScale biomeScale() {
        return biomeScale;
    }

    public void setBiomeScale(BiomeScale biomeScale) {
        this.biomeScale = biomeScale == null ? BiomeScale.NORMAL : biomeScale;
    }

    public TerrainStyle terrainStyle() {
        return terrainStyle;
    }

    public void setTerrainStyle(TerrainStyle terrainStyle) {
        this.terrainStyle = terrainStyle == null ? TerrainStyle.NORMAL : terrainStyle;
    }

    public int lastUnavailableCount() {
        return lastUnavailableCount;
    }

    public List<ResourceLocation> lastUnavailableIds() {
        return lastUnavailableIds;
    }

    public void select(ResourceLocation id, double weight) {
        if (weight <= 0.0d) {
            return;
        }
        selected.put(id, weight);
        focused = id;
    }

    public void selectWithDefault(BiomeEntry entry) {
        if (!selected.containsKey(entry.id())) {
            select(entry.id(), entry.defaultWeight());
        } else {
            focused = entry.id();
        }
    }

    public void deselect(ResourceLocation id) {
        selected.remove(id);
        if (id.equals(focused)) {
            focused = selected.isEmpty() ? null : selected.keySet().iterator().next();
        }
    }

    public void toggle(BiomeEntry entry) {
        if (isSelected(entry.id())) {
            deselect(entry.id());
        } else {
            selectWithDefault(entry);
        }
    }

    public void setWeight(ResourceLocation id, double weight) {
        if (!selected.containsKey(id) || weight <= 0.0d) {
            return;
        }
        selected.put(id, weight);
    }

    public double weightOf(ResourceLocation id) {
        return selected.getOrDefault(id, 0.0d);
    }

    public void resetWeight(BiomeEntry entry) {
        if (isSelected(entry.id())) {
            selected.put(entry.id(), entry.defaultWeight());
        }
    }

    public double totalWeight() {
        double sum = 0.0d;
        for (double w : selected.values()) {
            sum += w;
        }
        return sum;
    }

    public double relativePercent(ResourceLocation id) {
        double total = totalWeight();
        if (total <= 0.0d || !selected.containsKey(id)) {
            return 0.0d;
        }
        return 100.0d * selected.get(id) / total;
    }

    public void selectAll(Iterable<BiomeEntry> visible) {
        for (BiomeEntry entry : visible) {
            selectWithDefault(entry);
        }
    }

    public void clear() {
        selected.clear();
        focused = null;
        // Keep biomeScale / terrainStyle — options preferences, not selection.
    }

    /** Invert check state for currently visible biomes only. */
    public void invertVisible(Iterable<BiomeEntry> visible) {
        for (BiomeEntry entry : visible) {
            toggle(entry);
        }
    }

    /**
     * Additive random pick: sample {@code count} unique entries from {@code visible}
     * and add with default weights (existing weights unchanged).
     */
    public void randomPick(List<BiomeEntry> visible, int count) {
        if (visible.isEmpty() || count <= 0) {
            return;
        }
        int x = Math.min(count, visible.size());
        List<BiomeEntry> pool = new ArrayList<>(visible);
        Collections.shuffle(pool, ThreadLocalRandom.current());
        for (int i = 0; i < x; i++) {
            selectWithDefault(pool.get(i));
        }
    }

    /**
     * Drop ids not present in the registry; remember count for UI note.
     *
     * @return pruned (unavailable) ids
     */
    public List<ResourceLocation> pruneMissing(Set<ResourceLocation> present) {
        List<ResourceLocation> missing = new ArrayList<>();
        selected.entrySet().removeIf(e -> {
            if (!present.contains(e.getKey())) {
                missing.add(e.getKey());
                return true;
            }
            return false;
        });
        lastUnavailableCount = missing.size();
        lastUnavailableIds = List.copyOf(missing);
        if (focused != null && !selected.containsKey(focused)) {
            focused = selected.isEmpty() ? null : selected.keySet().iterator().next();
        }
        return lastUnavailableIds;
    }

    public void replaceFromConfig(CalicoWorldGenConfig config, Set<ResourceLocation> present) {
        clear();
        CalicoWorldGenConfig sanitized = config.sanitized();
        this.biomeScale = sanitized.biomeScale() == null ? BiomeScale.NORMAL : sanitized.biomeScale();
        this.terrainStyle = sanitized.terrainStyle() == null ? TerrainStyle.NORMAL : sanitized.terrainStyle();
        for (SelectedBiomeEntry entry : sanitized.selectedBiomes()) {
            ResourceLocation id = entry.resourceLocationOrNull();
            if (id == null) {
                continue;
            }
            selected.put(id, entry.weight());
        }
        pruneMissing(present);
        if (!selected.isEmpty()) {
            focused = selected.keySet().iterator().next();
        }
    }

    public CalicoWorldGenConfig toConfig() {
        List<SelectedBiomeEntry> list = new ArrayList<>(selected.size());
        selected.forEach((id, weight) -> list.add(new SelectedBiomeEntry(id.toString(), weight)));
        return CalicoWorldGenConfig.of(list, biomeScale, terrainStyle);
    }

    public void removeAll(Collection<ResourceLocation> ids) {
        for (ResourceLocation id : ids) {
            selected.remove(id);
        }
        if (focused != null && !selected.containsKey(focused)) {
            focused = selected.isEmpty() ? null : selected.keySet().iterator().next();
        }
    }

    public void putAllNew(Iterable<BiomeEntry> entries) {
        for (BiomeEntry entry : entries) {
            if (!selected.containsKey(entry.id())) {
                selected.put(entry.id(), entry.defaultWeight());
            }
        }
    }
}
