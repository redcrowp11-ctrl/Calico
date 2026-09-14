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
 * Virtualized tile grid: each list row holds {@link #COLUMNS} biome tiles.
 * Only visible rows are rendered (AbstractSelectionList / ObjectSelectionList).
 */
@OnlyIn(Dist.CLIENT)
public class BiomeTileGridWidget extends ObjectSelectionList<BiomeTileGridWidget.Row> {
    public static final int COLUMNS = 3;
    public static final int ROW_HEIGHT = 28;

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

    @Override
    public int getRowWidth() {
        return Math.max(220, this.width - 20);
    }

    public void setVisibleBiomes(List<BiomeEntry> biomes) {
        this.visible = List.copyOf(biomes);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < biomes.size(); i += COLUMNS) {
            int end = Math.min(i + COLUMNS, biomes.size());
            rows.add(new Row(biomes.subList(i, end)));
        }
        this.replaceEntries(rows);
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
            int tileW = Math.max(40, (width - 4) / COLUMNS);
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
                String label = check + entry.displayName().getString();
                if (selected) {
                    double pct = selection.relativePercent(entry.id());
                    label = label + " (" + Mth.floor(pct) + "%)";
                }
                graphics.drawString(
                        BiomeTileGridWidget.this.minecraft.font,
                        truncate(label, tileW - 8),
                        tx + 3,
                        ty + (height - 8) / 2,
                        0xFFFFFF);

                if (hoverTile) {
                    // Tooltip via screen; store for parent to show biome id
                    BiomeTileGridWidget.this.hoveredTooltipId = entry.id().toString();
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int left = BiomeTileGridWidget.this.getRowLeft();
            int width = BiomeTileGridWidget.this.getRowWidth();
            int tileW = Math.max(40, (width - 4) / COLUMNS);
            int top = BiomeTileGridWidget.this.getRowTop(
                    BiomeTileGridWidget.this.children().indexOf(this));
            if (mouseY < top || mouseY >= top + ROW_HEIGHT) {
                return false;
            }
            int col = (int) ((mouseX - left) / tileW);
            if (col >= 0 && col < tiles.size()) {
                BiomeEntry entry = tiles.get(col);
                if (button == 1) {
                    // Right-click focuses without toggle
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
