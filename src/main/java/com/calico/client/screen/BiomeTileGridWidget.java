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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Virtualized tile grid: each list row holds up to {@link #maxColumns()} biome tiles.
 * Prefer 2 columns when wide enough; otherwise 1. Two-line tiles for name + weight.
 */
@OnlyIn(Dist.CLIENT)
public class BiomeTileGridWidget extends ObjectSelectionList<BiomeTileGridWidget.Row> {
    /** Prefer 2 columns when list width is at least this many pixels. */
    public static final int TWO_COLUMN_MIN_WIDTH = 400;
    /** Two-line tiles: display name + weight/id line. */
    public static final int ROW_HEIGHT = 36;

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
    }

    /** 2 columns when width ≥ ~400px, else 1. */
    public int maxColumns() {
        return this.width >= TWO_COLUMN_MIN_WIDTH ? 2 : 1;
    }

    @Override
    public int getRowWidth() {
        return Math.max(180, this.width - 12);
    }

    /**
     * Rebuild rows from the visible biome list. Preserves scroll when the id sequence
     * is unchanged (e.g. caller rebuilds unnecessarily).
     */
    public void setVisibleBiomes(List<BiomeEntry> biomes) {
        double priorScroll = this.getScrollAmount();
        boolean sameOrder = sameBiomeIds(this.visible, biomes);
        this.visible = List.copyOf(biomes);
        if (sameOrder && !this.children().isEmpty()) {
            // Entry list already matches — skip replaceEntries to keep scroll/selection stable.
            return;
        }
        int cols = maxColumns();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < biomes.size(); i += cols) {
            int end = Math.min(i + cols, biomes.size());
            rows.add(new Row(biomes.subList(i, end)));
        }
        this.replaceEntries(rows);
        this.setScrollAmount(priorScroll);
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
    public class Row extends ObjectSelectionList.Entry<Row> {
        private final List<BiomeEntry> tiles;

        Row(List<BiomeEntry> tiles) {
            this.tiles = List.copyOf(tiles);
        }

        @Override
        public Component getNarration() {
            if (tiles.isEmpty()) {
                return Component.empty();
            }
            return tiles.getFirst().displayName();
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
            int cols = Math.max(1, maxColumns());
            int tileW = Math.max(80, (width - 4) / cols);
            var font = BiomeTileGridWidget.this.minecraft.font;

            for (int i = 0; i < tiles.size(); i++) {
                BiomeEntry entry = tiles.get(i);
                int tx = left + i * tileW;
                int ty = top;
                boolean selected = selection.isSelected(entry.id());
                boolean focused = entry.id().equals(selection.focused());
                boolean hoverTile = mouseX >= tx && mouseX < tx + tileW - 2
                        && mouseY >= ty && mouseY < ty + height;

                int bg = selected ? 0x80448AFF : (hoverTile ? 0x60FFFFFF : 0x40000000);
                graphics.fill(tx, ty, tx + tileW - 2, ty + height - 1, bg);
                if (focused) {
                    graphics.renderOutline(tx, ty, tileW - 2, height - 1, 0xFFFFFFFF);
                }

                String check = selected ? "[x] " : "[ ] ";
                String name = check + entry.displayName().getString();
                int textBudget = tileW - 10;
                graphics.drawString(font, truncate(name, textBudget), tx + 4, ty + 4, 0xFFFFFF);

                // Second line: relative % when selected; otherwise muted biome path hint when space allows
                if (selected) {
                    double pct = selection.relativePercent(entry.id());
                    String pctLabel = Mth.floor(pct) + "%";
                    int pctW = font.width(pctLabel);
                    graphics.drawString(font, pctLabel, tx + tileW - 6 - pctW, ty + 18, 0xFFEE88);
                } else {
                    String idHint = entry.id().getPath();
                    graphics.drawString(font, truncate(idHint, textBudget), tx + 4, ty + 18, 0x888888);
                }

                if (hoverTile) {
                    BiomeTileGridWidget.this.hoveredTooltipId = entry.id().toString();
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int left = BiomeTileGridWidget.this.getRowLeft();
            int width = BiomeTileGridWidget.this.getRowWidth();
            int cols = Math.max(1, maxColumns());
            int tileW = Math.max(80, (width - 4) / cols);
            int top = BiomeTileGridWidget.this.getRowTop(
                    BiomeTileGridWidget.this.children().indexOf(this));
            if (mouseY < top || mouseY >= top + ROW_HEIGHT) {
                return false;
            }
            int col = (int) ((mouseX - left) / tileW);
            if (col >= 0 && col < tiles.size()) {
                BiomeEntry entry = tiles.get(col);
                if (button == 1) {
                    selection.setFocused(entry.id());
                    onTileInteract.accept(entry);
                } else {
                    selection.toggle(entry);
                    onTileInteract.accept(entry);
                }
                return true;
            }
            return false;
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

    /** Unused helper kept for potential widget tooltips. */
    @SuppressWarnings("unused")
    private static Tooltip idTooltip(BiomeEntry entry) {
        return Tooltip.create(Component.literal(entry.id().toString()));
    }
}
