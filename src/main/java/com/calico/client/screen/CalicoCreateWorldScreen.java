package com.calico.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;


import com.calico.Calico;
import com.calico.client.data.BiomeCatalog;
import com.calico.client.data.BiomeEntry;
import com.calico.client.data.BiomeFilterState;
import com.calico.client.data.BiomePresets;
import com.calico.client.data.BiomeSelectionPersistence;
import com.calico.client.data.BiomeSelectionState;
import com.calico.client.data.BiomeTagClassifier;
import com.calico.config.BiomeScale;
import com.calico.config.CalicoConfigValidation;
import com.calico.config.CalicoWorldGenConfig;
import com.calico.fun.FunTabStub;
import com.calico.worldgen.BiomeDimension;
import com.calico.worldgen.BiomeRegistryDiscovery;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Calico create-world biome picker (Phase 1).
 * Opened via NeoForge {@code RegisterPresetEditorsEvent} for {@code calico:calico} — no mixin.
 */
@OnlyIn(Dist.CLIENT)
public class CalicoCreateWorldScreen extends Screen {
    private static final int SEARCH_DEBOUNCE_MS = 250;
    private static final double WEIGHT_MIN = 0.01d;
    private static final double WEIGHT_MAX = 100.0d;

    private static final int MARGIN = 8;
    private static final int GAP = 4;
    private static final int FOOTER_CLEAR = 36;
    private static final int DETAIL_MIN_SCREEN_WIDTH = 480;
    private static final int DETAIL_WIDTH = 160;
    private static final int BTN_H = 18;
    private static final int CHIP_H = 18;
    private static final int WRAP_ROW_STEP = 20;
    private static final int BTN_PAD = 12;
    private static final int BTN_MIN_W = 44;

    /** Wordmark texture (800×280). Drawn scaled in the header. */
    private static final ResourceLocation WORDMARK =
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "textures/gui/logo.png");
    private static final int WORDMARK_TEX_W = 800;
    private static final int WORDMARK_TEX_H = 280;
    private static final int WORDMARK_DRAW_H = 26;
    private static final int WORDMARK_DRAW_W = WORDMARK_DRAW_H * WORDMARK_TEX_W / WORDMARK_TEX_H;

    private final CreateWorldScreen parent;
    private final WorldCreationContext context;

    private BiomeCatalog catalog;
    private final BiomeSelectionState selection = new BiomeSelectionState();
    private final BiomeFilterState filters = new BiomeFilterState();

    private BiomeTileGridWidget grid;
    private EditBox searchBox;
    private Button createButton;
    private Button resetWeightButton;
    private WeightSlider weightSlider;
    private Component statusMessage = Component.empty();
    private Component unavailableNote = Component.empty();
    private Component relativePercentLabel = Component.empty();
    private Component selectedCountLabel = Component.empty();
    private Component gateMessage = Component.empty();
    private Component lockedFreqLabel = Component.empty();

    private String pendingSearch = "";
    private long searchDirtyAtMs = 0L;
    private boolean searchDirty = false;

    private int contentTop;
    private int contentBottom;
    private int detailLeft;
    private int detailWidth;
    private boolean sideDetail;
    private boolean showLockedFreq;

    /** Layout cursor for wrapping toolbar rows. */
    private int wrapX;
    private int wrapY;
    private int wrapMaxX;
    private int wrapStartX;

    public CalicoCreateWorldScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(Component.translatable("calico.screen.create.title"));
        this.parent = parent;
        this.context = context;
    }

    /** Factory for {@link PresetEditor}. */
    public static Screen create(CreateWorldScreen lastScreen, WorldCreationContext context) {
        return new CalicoCreateWorldScreen(lastScreen, context);
    }

    @Override
    protected void init() {
        FunTabStub.noop(); // Phase 1: Fun tab stub only — no Fun chrome

        this.catalog = BiomeCatalog.build(
                BiomeRegistryDiscovery.biomeLookup(this.context.worldgenLoadContext()));

        // Remember last selection
        BiomeSelectionPersistence.load(this.minecraft).ifPresent(cfg ->
                this.selection.replaceFromConfig(cfg, this.catalog.presentIds()));
        updateUnavailableNote();

        this.sideDetail = this.width >= DETAIL_MIN_SCREEN_WIDTH;
        this.detailWidth = this.sideDetail ? DETAIL_WIDTH : 0;
        this.detailLeft = this.sideDetail ? this.width - MARGIN - DETAIL_WIDTH : MARGIN;
        this.contentBottom = this.height - FOOTER_CLEAR;

        // --- Header wordmark reserve, then Row 1: dimension tabs ---
        int headerBottom = 4 + WORDMARK_DRAW_H; // logo drawn at y=4
        int tabY = headerBottom + 4;
        int tabX = MARGIN;
        int tabW = 78;
        int tabH = 20;
        tabX += addDimTab(tabX, tabY, tabW, tabH, BiomeDimension.OVERWORLD, "calico.screen.create.tab.overworld") + GAP;
        tabX += addDimTab(tabX, tabY, tabW, tabH, BiomeDimension.NETHER, "calico.screen.create.tab.nether") + GAP;
        addDimTab(tabX, tabY, tabW, tabH, BiomeDimension.END, "calico.screen.create.tab.end");

        // --- Row 2: search (wider) + vanilla/modded cycle ---
        int row2Y = tabY + tabH + GAP;
        int searchW = Mth.clamp(this.width / 3, 180, 220);
        this.searchBox = new EditBox(this.font, MARGIN, row2Y, searchW, BTN_H,
                Component.translatable("calico.screen.create.search"));
        this.searchBox.setHint(Component.translatable("calico.screen.create.search.hint"));
        this.searchBox.setResponder(value -> {
            this.pendingSearch = value;
            this.searchDirtyAtMs = System.currentTimeMillis();
            this.searchDirty = true;
        });
        this.searchBox.setValue(this.filters.searchQuery());
        addRenderableWidget(this.searchBox);

        int cycleX = MARGIN + searchW + GAP;
        int cycleW = Math.max(100, this.font.width(Component.translatable("calico.screen.create.filter.vanilla").getString()) + 48);
        addRenderableWidget(CycleButton.<BiomeFilterState.VanillaModdedFilter>builder(v -> Component.translatable(
                        switch (v) {
                            case ALL -> "calico.screen.create.filter.all";
                            case VANILLA_ONLY -> "calico.screen.create.filter.vanilla";
                            case MODDED_ONLY -> "calico.screen.create.filter.modded";
                        }))
                .withValues(BiomeFilterState.VanillaModdedFilter.values())
                .withInitialValue(this.filters.vanillaModded())
                .create(cycleX, row2Y, cycleW, BTN_H, Component.translatable("calico.screen.create.filter.source"),
                        (btn, value) -> {
                            this.filters.setVanillaModded(value);
                            refreshGrid();
                        }));

        // --- Row 3: tag chips (wrap) ---
        beginWrap(MARGIN, row2Y + BTN_H + GAP, this.width - MARGIN);
        addChipWrapped(Component.translatable("calico.screen.create.chip.hot"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.HOT);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.HOT));
        addChipWrapped(Component.translatable("calico.screen.create.chip.temp"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.TEMPERATE);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.TEMPERATE));
        addChipWrapped(Component.translatable("calico.screen.create.chip.cold"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.COLD);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.COLD));
        addChipWrapped(Component.translatable("calico.screen.create.chip.dry"),
                () -> {
                    this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.DRY);
                    refreshGrid();
                }, () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.DRY));
        addChipWrapped(Component.translatable("calico.screen.create.chip.humid"),
                () -> {
                    this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.HUMID);
                    refreshGrid();
                }, () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.HUMID));
        addChipWrapped(Component.translatable("calico.screen.create.chip.precip"),
                () -> {
                    this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS);
                    refreshGrid();
                }, () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS));
        addChipWrapped(Component.translatable("calico.screen.create.chip.no_precip"),
                () -> {
                    this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS_NOT);
                    refreshGrid();
                }, () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS_NOT));

        // --- Row 4: QoL + presets (wrap) ---
        beginWrap(MARGIN, this.wrapY + WRAP_ROW_STEP, this.width - MARGIN);
        addActionWrapped("calico.screen.create.qol.select_all", this::selectAllVisible);
        addActionWrapped("calico.screen.create.qol.clear", () -> {
            this.selection.clear();
            onSelectionChanged();
        });
        addActionWrapped("calico.screen.create.qol.invert", this::invertVisible);
        addActionWrapped("calico.screen.create.preset.deserts", () -> {
            BiomePresets.applyDeserts(this.catalog, this.selection);
            onSelectionChanged();
        });
        addActionWrapped("calico.screen.create.preset.cold", () -> {
            BiomePresets.applyCold(this.catalog, this.selection);
            onSelectionChanged();
        });
        addActionWrapped("calico.screen.create.preset.no_oceans", () -> {
            BiomePresets.applyNoOceans(this.catalog, this.selection);
            onSelectionChanged();
        });
        addActionWrapped("calico.screen.create.qol.random", this::openRandomPick);
        addActionWrapped("calico.screen.create.qol.export", this::doExport);
        addActionWrapped("calico.screen.create.qol.import", this::doImport);

        // Biome scale: Normal (default contiguous) vs Quilt (tight patchwork)
        // DevBotAid binds the same field via FunTabStub.OPTION_BIOME_SCALE / config.biomeScale
        addBiomeScaleCycleWrapped();

        // Raise contentTop enough for wrapped chrome
        this.contentTop = this.wrapY + WRAP_ROW_STEP + GAP;

        // Grid + detail geometry
        int gridRight;
        int gridBottom;
        if (this.sideDetail) {
            gridRight = this.detailLeft - GAP;
            gridBottom = this.contentBottom;
        } else {
            // Narrow: full-width grid; reserve a strip for frequency controls above footer
            gridRight = this.width - MARGIN;
            gridBottom = this.contentBottom - 52;
            this.detailLeft = MARGIN;
            this.detailWidth = Math.min(220, this.width - 2 * MARGIN);
        }

        int gridWidth = Math.max(120, gridRight - MARGIN);
        int gridHeight = Math.max(40, gridBottom - this.contentTop);
        this.grid = new BiomeTileGridWidget(
                this.minecraft, gridWidth, gridHeight, this.contentTop, this.selection, entry -> onSelectionChanged());
        this.grid.setX(MARGIN);
        addRenderableWidget(this.grid);

        // Detail pane: frequency slider + reset
        int freqY = this.sideDetail ? this.contentTop + 24 : gridBottom + 4;
        this.weightSlider = new WeightSlider(this.detailLeft, freqY, this.detailWidth > 0 ? this.detailWidth : 160, 20);
        addRenderableWidget(this.weightSlider);
        this.resetWeightButton = Button.builder(Component.translatable("calico.screen.create.freq.reset"), b -> {
                    ResourceLocation focused = this.selection.focused();
                    if (focused != null) {
                        BiomeEntry entry = this.catalog.get(focused);
                        if (entry != null) {
                            this.selection.resetWeight(entry);
                            onWeightChanged(false);
                        }
                    }
                }).bounds(this.detailLeft, freqY + 24, this.detailWidth > 0 ? this.detailWidth : 160, BTN_H).build();
        addRenderableWidget(this.resetWeightButton);

        // Footer (~36px clear)
        int footerY = this.height - 28;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 154, footerY, 100, 20).build());
        this.createButton = Button.builder(Component.translatable("calico.screen.create.done"), b -> onCreate())
                .bounds(this.width / 2 - 50, footerY, 100, 20).build();
        this.createButton.setTooltip(Tooltip.create(Component.translatable("calico.screen.create.gate")));
        addRenderableWidget(this.createButton);

        refreshGrid();
        onSelectionChanged();
    }

    private void beginWrap(int startX, int startY, int maxX) {
        this.wrapStartX = startX;
        this.wrapX = startX;
        this.wrapY = startY;
        this.wrapMaxX = maxX;
    }

    private int placeWrapped(int w) {
        if (this.wrapX + w > this.wrapMaxX && this.wrapX > this.wrapStartX) {
            this.wrapX = this.wrapStartX;
            this.wrapY += WRAP_ROW_STEP;
        }
        int placedX = this.wrapX;
        this.wrapX += w + GAP;
        return placedX;
    }

    private int textWidth(Component label) {
        return this.font.width(label);
    }

    private int sizedWidth(Component label) {
        return Math.max(BTN_MIN_W, textWidth(label) + BTN_PAD);
    }

    private int addDimTab(int x, int y, int w, int h, BiomeDimension dim, String key) {
        Button btn = Button.builder(Component.translatable(key), b -> {
            this.filters.setDimensionTab(dim);
            refreshGrid();
        }).bounds(x, y, w, h).build();
        addRenderableWidget(btn);
        return w;
    }

    private void addChipWrapped(Component label, Runnable action, BooleanSupplier active) {
        int w = sizedWidth(label);
        int x = placeWrapped(w);
        Button btn = Button.builder(label, b -> {
            action.run();
            b.setMessage(active.getAsBoolean()
                    ? Component.literal("*").append(label)
                    : label);
        }).bounds(x, this.wrapY, w, CHIP_H).build();
        if (active.getAsBoolean()) {
            btn.setMessage(Component.literal("*").append(label));
        }
        addRenderableWidget(btn);
    }


    /** CycleButton for biomeScale — Normal (default) / Quilt. Hook for DevBotAid. */
    private void addBiomeScaleCycleWrapped() {
        Component label = Component.translatable("calico.screen.create.scale");
        // Estimate width similar to other cycle buttons
        int w = Math.max(sizedWidth(label) + 40, 120);
        int x = placeWrapped(w);
        addRenderableWidget(CycleButton.<BiomeScale>builder(v -> Component.translatable(
                        switch (v) {
                            case NORMAL -> "calico.screen.create.scale.normal";
                            case QUILT -> "calico.screen.create.scale.quilt";
                        }))
                .withValues(BiomeScale.values())
                .withInitialValue(this.selection.biomeScale())
                .withTooltip(value -> Tooltip.create(Component.translatable(
                        value.isQuilt()
                                ? "calico.screen.create.scale.quilt.tooltip"
                                : "calico.screen.create.scale.normal.tooltip")))
                .create(x, this.wrapY, w, BTN_H, label,
                        (btn, value) -> this.selection.setBiomeScale(value)));
    }

    private void addActionWrapped(String key, Runnable action) {
        Component label = Component.translatable(key);
        int w = sizedWidth(label);
        int x = placeWrapped(w);
        addRenderableWidget(Button.builder(label, b -> action.run())
                .bounds(x, this.wrapY, w, BTN_H).build());
    }

    private void refreshGrid() {
        List<BiomeEntry> visible = this.catalog.filter(this.filters);
        this.grid.setVisibleBiomes(visible);
    }

    private void selectAllVisible() {
        this.selection.selectAll(this.grid.visibleBiomes());
        onSelectionChanged();
    }

    private void invertVisible() {
        this.selection.invertVisible(new ArrayList<>(this.grid.visibleBiomes()));
        onSelectionChanged();
    }

    private void openRandomPick() {
        List<BiomeEntry> visible = this.grid.visibleBiomes();
        this.minecraft.setScreen(new RandomPickScreen(this, visible.size(), count -> {
            this.selection.randomPick(visible, count);
            onSelectionChanged();
        }));
    }

    private void doExport() {
        ExportImportHelper.exportToClipboard(this.minecraft, this.selection.toConfig());
        this.statusMessage = Component.translatable("calico.screen.create.export.ok");
    }

    private void doImport() {
        Optional<CalicoWorldGenConfig> parsed = ExportImportHelper.importFromClipboard(this.minecraft);
        if (parsed.isEmpty()) {
            this.statusMessage = Component.translatable("calico.screen.create.import.fail");
            return;
        }
        this.selection.replaceFromConfig(parsed.get(), this.catalog.presentIds());
        updateUnavailableNote();
        BiomeSelectionPersistence.save(this.minecraft, this.selection.toConfig());
        onSelectionChanged();
        this.statusMessage = Component.translatable("calico.screen.create.import.ok");
    }

    private void updateUnavailableNote() {
        int n = this.selection.lastUnavailableCount();
        if (n > 0) {
            this.unavailableNote = Component.translatable("calico.screen.create.unavailable", n);
        } else {
            this.unavailableNote = Component.empty();
        }
    }

    private void onSelectionChanged() {
        updateSelectionChrome();
        // Tile badges read selection live — do not rebuild the grid entry list here.
    }

    /**
     * Weight slider tick: update weight + labels only. Avoid full grid rebuild.
     *
     * @param fromSliderDrag true while dragging (labels only; badges already live)
     */
    private void onWeightChanged(boolean fromSliderDrag) {
        updateFreqLabelsOnly();
        // Badges render from selection state each frame — no refreshGrid().
        if (!fromSliderDrag) {
            // On release / discrete reset, still skip replaceEntries; scroll stays put.
        }
    }

    private void updateSelectionChrome() {
        this.selectedCountLabel = Component.translatable(
                "calico.screen.create.selected", this.selection.selectedCount());

        CalicoWorldGenConfig config = this.selection.toConfig();
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(config);
        this.createButton.active = result.valid();
        this.gateMessage = result.valid()
                ? Component.empty()
                : Component.translatable("calico.screen.create.gate");

        updateFreqControls();
    }

    private void updateFreqLabelsOnly() {
        ResourceLocation focused = this.selection.focused();
        boolean hasFocusedSelected = focused != null && this.selection.isSelected(focused);
        if (hasFocusedSelected) {
            if (this.selection.selectedCount() == 1) {
                this.relativePercentLabel = Component.translatable("calico.screen.create.freq.percent", "100.0");
                this.lockedFreqLabel = Component.translatable("calico.screen.create.freq.locked");
            } else {
                this.relativePercentLabel = Component.translatable(
                        "calico.screen.create.freq.percent",
                        String.format("%.1f", this.selection.relativePercent(focused)));
                this.lockedFreqLabel = Component.empty();
            }
        } else {
            this.relativePercentLabel = Component.translatable("calico.screen.create.freq.percent_none");
            this.lockedFreqLabel = Component.empty();
        }
    }

    private void updateFreqControls() {
        ResourceLocation focused = this.selection.focused();
        boolean hasFocusedSelected = focused != null && this.selection.isSelected(focused);
        boolean single = this.selection.selectedCount() == 1 && hasFocusedSelected;
        this.showLockedFreq = single;

        // 1 biome selected: lock/hide frequency slider (always 100%)
        this.weightSlider.visible = hasFocusedSelected && !single;
        this.weightSlider.active = hasFocusedSelected && !single;
        this.resetWeightButton.visible = hasFocusedSelected && !single;
        this.resetWeightButton.active = hasFocusedSelected && !single;

        if (hasFocusedSelected && !single) {
            double w = this.selection.weightOf(focused);
            this.weightSlider.setWeight(w);
        }
        updateFreqLabelsOnly();
    }

    private void onCreate() {
        CalicoWorldGenConfig config = this.selection.toConfig();
        if (!CalicoWorldCreationBridge.submit(this.parent, config)) {
            this.gateMessage = Component.translatable("calico.screen.create.gate");
            this.createButton.active = false;
            return;
        }
        BiomeSelectionPersistence.save(this.minecraft, config);
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.searchDirty && System.currentTimeMillis() - this.searchDirtyAtMs >= SEARCH_DEBOUNCE_MS) {
            this.searchDirty = false;
            this.filters.setSearchQuery(this.pendingSearch);
            refreshGrid();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Clear per-frame hover tooltip capture before list renders
        this.grid.hoveredTooltipId = null;
        super.render(graphics, mouseX, mouseY, partialTick);

        int logoX = this.width / 2 - WORDMARK_DRAW_W / 2;
        int logoY = 4;
        graphics.blit(
                WORDMARK,
                logoX,
                logoY,
                WORDMARK_DRAW_W,
                WORDMARK_DRAW_H,
                0.0f,
                0.0f,
                WORDMARK_TEX_W,
                WORDMARK_TEX_H,
                WORDMARK_TEX_W,
                WORDMARK_TEX_H);
        graphics.drawString(this.font, this.selectedCountLabel,
                this.width - MARGIN - this.font.width(this.selectedCountLabel), 8, 0xFFEE88);

        // Detail / frequency labels
        int freqTitleY = this.sideDetail ? this.contentTop + 4
                : (this.grid.getY() + this.grid.getHeight() + 2);
        graphics.drawString(this.font, Component.translatable("calico.screen.create.freq.title"),
                this.detailLeft, freqTitleY, 0xFFFFFF);

        if (this.showLockedFreq) {
            graphics.drawString(this.font, this.lockedFreqLabel,
                    this.detailLeft, freqTitleY + 22, 0xFFEE88);
            graphics.drawString(this.font, this.relativePercentLabel,
                    this.detailLeft, freqTitleY + 38, 0xAAAAAA);
        } else {
            int pctY = this.sideDetail ? this.contentTop + 72
                    : freqTitleY + 48;
            graphics.drawString(this.font, this.relativePercentLabel, this.detailLeft, pctY, 0xAAAAAA);
        }

        // Preview placeholder (side detail only — shrink/omit on narrow)
        if (this.sideDetail) {
            int previewTop = this.contentTop + 96;
            int previewH = Math.min(80, Math.max(40, this.contentBottom - previewTop - 16));
            graphics.fill(this.detailLeft, previewTop, this.detailLeft + this.detailWidth, previewTop + previewH, 0x40000000);
            graphics.drawCenteredString(this.font,
                    Component.translatable("calico.screen.create.preview.placeholder"),
                    this.detailLeft + this.detailWidth / 2, previewTop + previewH / 2 - 4, 0x888888);

            graphics.drawString(this.font, Component.translatable("calico.fun.coming_soon"),
                    this.detailLeft, this.contentBottom - 12, 0x666666);
        }

        if (!this.unavailableNote.getString().isEmpty()) {
            graphics.drawString(this.font, this.unavailableNote, MARGIN, this.height - 44, 0xFFAA66);
        }
        if (!this.gateMessage.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.gateMessage, this.width / 2, this.height - 44, 0xFF6666);
        }
        if (!this.statusMessage.getString().isEmpty()) {
            graphics.drawString(this.font, this.statusMessage, MARGIN, this.height - 56, 0x88FF88);
        }

        // Biome-id tooltip on hover
        String tip = this.grid.consumeHoveredTooltipId();
        if (tip != null) {
            graphics.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        // Persist non-empty selection on Back
        CalicoWorldGenConfig config = this.selection.toConfig();
        if (!config.selectedBiomes().isEmpty()) {
            BiomeSelectionPersistence.save(this.minecraft, config);
        }
        this.minecraft.setScreen(this.parent);
    }

    @OnlyIn(Dist.CLIENT)
    private class WeightSlider extends AbstractSliderButton {
        private boolean dragging;

        WeightSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), weightToValue(BiomeTagClassifier.DEFAULT_WEIGHT));
            updateMessage();
        }

        void setWeight(double weight) {
            this.value = weightToValue(weight);
            updateMessage();
        }

        private static double weightToValue(double weight) {
            double clamped = Mth.clamp(weight, WEIGHT_MIN, WEIGHT_MAX);
            return (clamped - WEIGHT_MIN) / (WEIGHT_MAX - WEIGHT_MIN);
        }

        private double currentWeight() {
            return WEIGHT_MIN + this.value * (WEIGHT_MAX - WEIGHT_MIN);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable(
                    "calico.screen.create.freq.slider", String.format("%.2f", currentWeight())));
        }

        @Override
        protected void applyValue() {
            ResourceLocation focused = selection.focused();
            if (focused != null && selection.isSelected(focused)) {
                selection.setWeight(focused, currentWeight());
                // Labels/% only — do not rebuild grid entry list on every tick
                onWeightChanged(true);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if (this.dragging) {
                this.dragging = false;
                // Final label sync on release (still no full refreshGrid)
                onWeightChanged(false);
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            this.dragging = true;
            super.onClick(mouseX, mouseY);
        }
    }
}
