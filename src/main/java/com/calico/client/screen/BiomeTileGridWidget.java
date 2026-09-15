package com.calico.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.calico.client.data.BiomeEntry;
import com.calico.client.data.BiomeSelectionState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Vanilla-style single-column biome selection list (buffet picker spirit, multi-select).
 * One biome per row: checkbox + readable name + optional relative %.
 */
@OnlyIn(Dist.CLIENT)
public class BiomeTileGridWidget extends ObjectSelectionList<BiomeTileGridWidget.Entry> {
    /** Comfortable vanilla-adjacent row height (buffet uses 16; we want ~20–24+). */
    public static final int ROW_HEIGHT = 24;

    private final BiomeSelectionState selection;
    private final Consumer<BiomeEntry> onTileInteract;
    private List<BiomeEntry> visible = List.of();

    public BiomeTileGridWidget(
            Minecraft minecraft,
            int width,
            int height,
            int y,
            BiomeSelectionState selection,
            Consumer<BiomeEntry> onTileInteract) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        this.selection = selection;
        this.onTileInteract = onTileInteract;
        this.centerListVertically = false;
    }

    @Override
    public int getRowWidth() {
        return Math.max(220, this.width - 20);
    }

    @Override
    protected int getScrollbarPosition() {
        return this.getX() + this.getRowWidth() + 6;
    }

    /**
     * Rebuild rows from the visible biome list. Preserves scroll when the id sequence
     * is unchanged.
     */
    public void setVisibleBiomes(List<BiomeEntry> biomes) {
        double priorScroll = this.getScrollAmount();
        boolean sameOrder = sameBiomeIds(this.visible, biomes);
        this.visible = List.copyOf(biomes);
        if (sameOrder && !this.children().isEmpty()) {
            syncFocusedSelection();
            return;
        }
        List<Entry> rows = new ArrayList<>(biomes.size());
        for (BiomeEntry biome : biomes) {
            rows.add(new Entry(biome));
        }
        this.replaceEntries(rows);
        this.setScrollAmount(priorScroll);
        syncFocusedSelection();
    }

    private void syncFocusedSelection() {
        var focusedId = this.selection.focused();
        if (focusedId == null) {
            this.setSelected(null);
            return;
        }
        for (Entry entry : this.children()) {
            if (entry.biome.id().equals(focusedId)) {
                this.setSelected(entry);
                return;
            }
        }
        this.setSelected(null);
    }

    private static boolean sameBiomeIds(List<BiomeEntry> a, List<BiomeEntry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id())) {
                return false;
            }
        }
        return true;
    }

    public List<BiomeEntry> visibleBiomes() {
        return visible;
    }

    @OnlyIn(Dist.CLIENT)
    public class Entry extends ObjectSelectionList.Entry<Entry> {
        private final BiomeEntry biome;

        Entry(BiomeEntry biome) {
            this.biome = biome;
        }

        public BiomeEntry biome() {
            return this.biome;
        }

        @Override
        public Component getNarration() {
            boolean selected = selection.isSelected(biome.id());
            return Component.translatable(
                    selected ? "narrator.select" : "narrator.select",
                    biome.displayName());
        }

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick) {
            var font = BiomeTileGridWidget.this.minecraft.font;
            boolean selected = selection.isSelected(biome.id());

            String check = selected ? "[x] " : "[ ] ";
            String name = check + biome.displayName().getString();
            int textY = top + (height - 8) / 2;
            int textBudget = width - 12;
            if (selected) {
                String pctLabel = Mth.floor(selection.relativePercent(biome.id())) + "%";
                int pctW = font.width(pctLabel);
                textBudget = Math.max(40, width - 16 - pctW);
                graphics.drawString(font, pctLabel, left + width - 6 - pctW, textY, 0xFFEE88);
            }
            graphics.drawString(font, truncate(name, textBudget), left + 5, textY, 0xFFFFFF);

            if (hovering) {
                BiomeTileGridWidget.this.hoveredTooltipId = biome.id().toString();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 1) {
                // Right-click: focus only (for frequency strip)
                selection.setFocused(biome.id());
                BiomeTileGridWidget.this.setSelected(this);
                onTileInteract.accept(biome);
            } else {
                selection.toggle(biome);
                BiomeTileGridWidget.this.setSelected(this);
                onTileInteract.accept(biome);
            }
            return true;
        }
    }

    @Nullable
    String hoveredTooltipId;

    @Nullable
    public String consumeHoveredTooltipId() {
        String id = hoveredTooltipId;
        hoveredTooltipId = null;
        return id;
    }

    private static String truncate(String text, int maxPx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font.width(text) <= maxPx) {
            return text;
        }
        String ellipsis = "...";
        int budget = maxPx - mc.font.width(ellipsis);
        if (budget <= 0) {
            return ellipsis;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (mc.font.width(sb.toString()) > budget) {
                sb.setLength(Math.max(0, sb.length() - 1));
                break;
            }
        }
        return sb + ellipsis;
    }
}
