package com.calico.client.screen;

import java.util.List;
import com.calico.Calico;
import com.calico.client.data.BiomeCatalog;
import com.calico.client.data.BiomeEntry;
import com.calico.client.data.BiomeFilterState;
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
 * Calico create-world biome picker — vanilla buffet spirit with multi-select.
 * Main chrome stays sparse; climate chips / QoL / presets live in {@link CalicoFiltersScreen}.
 */
@OnlyIn(Dist.CLIENT)
public class CalicoCreateWorldScreen extends Screen {
    private static final int SEARCH_DEBOUNCE_MS = 250;
    private static final double WEIGHT_MIN = 0.01d;
    private static final double WEIGHT_MAX = 100.0d;

    private static final int MARGIN = 10;
    private static final int GAP = 8;
    private static final int BTN_H = 20;
    private static final int FOOTER_H = 28;
    private static final int DETAIL_STRIP_H = 48;

    /** Wordmark texture (800×280). Drawn scaled in the header. */
    private static final ResourceLocation WORDMARK =
            ResourceLocation.fromNamespaceAndPath(Calico.MOD_ID, "textures/gui/logo.png");
    private static final int WORDMARK_TEX_W = 800;
    private static final int WORDMARK_TEX_H = 280;
    private static final int WORDMARK_DRAW_H = 24;
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
    private boolean persistenceLoaded = false;

    private int listTop;
    private int listBottom;
    private int detailY;
    private boolean showDetailStrip;
    private boolean showLockedFreq;

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

        // Load last selection once (not on every resize / child-screen return)
        if (!this.persistenceLoaded) {
            BiomeSelectionPersistence.load(this.minecraft).ifPresent(cfg ->
                    this.selection.replaceFromConfig(cfg, this.catalog.presentIds()));
            this.persistenceLoaded = true;
            updateUnavailableNote();
        }

        // --- Header: wordmark reserve ---
        int headerBottom = 6 + WORDMARK_DRAW_H;

        // --- Toolbar row: Dimension | Search | Filters… | Biome scale ---
        int toolbarY = headerBottom + 8;
        int x = MARGIN;

        int dimW = 120;
        addRenderableWidget(CycleButton.<BiomeDimension>builder(d -> Component.translatable(
                        switch (d) {
                            case OVERWORLD -> "calico.screen.create.tab.overworld";
                            case NETHER -> "calico.screen.create.tab.nether";
                            case END -> "calico.screen.create.tab.end";
                            case UNKNOWN -> "calico.screen.create.tab.overworld";
                        }))
                .withValues(BiomeDimension.OVERWORLD, BiomeDimension.NETHER, BiomeDimension.END)
                .withInitialValue(this.filters.dimensionTab() == BiomeDimension.UNKNOWN
                        ? BiomeDimension.OVERWORLD
                        : this.filters.dimensionTab())
                .create(x, toolbarY, dimW, BTN_H,
                        Component.translatable("calico.screen.create.dimension"),
                        (btn, value) -> {
                            this.filters.setDimensionTab(value);
                            refreshGrid();
                        }));
        x += dimW + GAP;

        int filtersW = Math.max(80, this.font.width(Component.translatable("calico.screen.create.filters")) + 16);
        int scaleW = 150;
        int searchW = Math.max(120, this.width - MARGIN - x - filtersW - scaleW - GAP * 2 - MARGIN);
        this.searchBox = new EditBox(this.font, x, toolbarY, searchW, BTN_H,
                Component.translatable("calico.screen.create.search"));
        this.searchBox.setHint(Component.translatable("calico.screen.create.search.hint"));
        this.searchBox.setResponder(value -> {
            this.pendingSearch = value;
            this.searchDirtyAtMs = System.currentTimeMillis();
            this.searchDirty = true;
        });
        this.searchBox.setValue(this.filters.searchQuery());
        addRenderableWidget(this.searchBox);
        x += searchW + GAP;

        addRenderableWidget(Button.builder(Component.translatable("calico.screen.create.filters"), b -> openFilters())
                .bounds(x, toolbarY, filtersW, BTN_H).build());
        x += filtersW + GAP;

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
                .create(x, toolbarY, scaleW, BTN_H,
                        Component.translatable("calico.screen.create.scale"),
                        (btn, value) -> this.selection.setBiomeScale(value)));

        this.listTop = toolbarY + BTN_H + GAP;

        // Reserve slim frequency strip above footer when a selected biome is focused
        this.showDetailStrip = shouldShowDetailStrip();
        int footerClear = FOOTER_H + 8;
        this.listBottom = this.height - footerClear - (this.showDetailStrip ? DETAIL_STRIP_H : 0);
        this.detailY = this.listBottom + 6;

        int listHeight = Math.max(40, this.listBottom - this.listTop);
        int listWidth = this.width; // full-width like buffet ObjectSelectionList
        this.grid = new BiomeTileGridWidget(
                this.minecraft, listWidth, listHeight, this.listTop, this.selection, entry -> onSelectionChanged());
        this.grid.setX(0);
        addRenderableWidget(this.grid);

        // Slim detail strip: frequency slider + reset (hidden unless focused selected + multi)
        int stripInnerW = Math.min(280, this.width - 2 * MARGIN);
        int stripX = this.width / 2 - stripInnerW / 2;
        this.weightSlider = new WeightSlider(stripX, this.detailY + 14, stripInnerW - 110, 20);
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
                }).bounds(stripX + stripInnerW - 100, this.detailY + 14, 100, BTN_H).build();
        addRenderableWidget(this.resetWeightButton);

        // Footer
        int footerY = this.height - FOOTER_H;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 155, footerY, 150, 20).build());
        this.createButton = Button.builder(Component.translatable("calico.screen.create.done"), b -> onCreate())
                .bounds(this.width / 2 + 5, footerY, 150, 20).build();
        this.createButton.setTooltip(Tooltip.create(Component.translatable("calico.screen.create.gate")));
        addRenderableWidget(this.createButton);

        refreshGrid();
        onSelectionChanged();
    }

    private boolean shouldShowDetailStrip() {
        ResourceLocation focused = this.selection.focused();
        return focused != null && this.selection.isSelected(focused);
    }

    private void openFilters() {
        this.minecraft.setScreen(new CalicoFiltersScreen(this, this.catalog, this.selection, this.filters));
    }

    /** Called by {@link CalicoFiltersScreen} after selection mutations. */
    void notifySelectionChangedFromFilters() {
        // Selection mutated while this screen is not current; chrome refreshes on return.
    }

    /** Called by {@link CalicoFiltersScreen} after import prune. */
    void updateUnavailableNoteFromFilters() {
        updateUnavailableNote();
    }

    /** Called when Filters screen closes — re-init will refresh list. */
    void onFiltersClosed() {
        // no-op; init() refreshes grid from preserved filter/selection state
    }

    private void refreshGrid() {
        List<BiomeEntry> visible = this.catalog.filter(this.filters);
        this.grid.setVisibleBiomes(visible);
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
        boolean needDetail = shouldShowDetailStrip();
        if (needDetail != this.showDetailStrip) {
            // Rebuild layout so list height / strip visibility stay in sync
            this.rebuildWidgets();
            return;
        }
        updateSelectionChrome();
    }

    private void onWeightChanged(boolean fromSliderDrag) {
        updateFreqLabelsOnly();
        if (!fromSliderDrag) {
            // Final release — badges already live from selection state
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

        this.weightSlider.visible = hasFocusedSelected && !single && this.showDetailStrip;
        this.weightSlider.active = hasFocusedSelected && !single;
        this.resetWeightButton.visible = hasFocusedSelected && !single && this.showDetailStrip;
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
        this.grid.hoveredTooltipId = null;
        super.render(graphics, mouseX, mouseY, partialTick);

        int logoX = this.width / 2 - WORDMARK_DRAW_W / 2;
        int logoY = 6;
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
                this.width - MARGIN - this.font.width(this.selectedCountLabel), 10, 0xFFEE88);

        if (this.showDetailStrip) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("calico.screen.create.freq.title"),
                    this.width / 2, this.detailY, 0xFFFFFF);
            if (this.showLockedFreq) {
                graphics.drawCenteredString(this.font, this.lockedFreqLabel,
                        this.width / 2, this.detailY + 18, 0xFFEE88);
                graphics.drawCenteredString(this.font, this.relativePercentLabel,
                        this.width / 2, this.detailY + 32, 0xAAAAAA);
            } else if (this.weightSlider.visible) {
                graphics.drawString(this.font, this.relativePercentLabel,
                        MARGIN, this.detailY + 18, 0xAAAAAA);
            }
        }

        int noteY = this.height - FOOTER_H - 12;
        if (!this.unavailableNote.getString().isEmpty()) {
            graphics.drawString(this.font, this.unavailableNote, MARGIN, noteY, 0xFFAA66);
        }
        if (!this.gateMessage.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.gateMessage, this.width / 2, noteY, 0xFF6666);
        }
        if (!this.statusMessage.getString().isEmpty()) {
            graphics.drawString(this.font, this.statusMessage, MARGIN, noteY - 12, 0x88FF88);
        }

        // Fun stub whisper — muted, above footer, no chrome
        if (!this.showDetailStrip) {
            graphics.drawString(this.font, Component.translatable("calico.fun.coming_soon"),
                    MARGIN, this.height - FOOTER_H - 14, 0x444444);
        }

        String tip = this.grid.consumeHoveredTooltipId();
        if (tip != null) {
            graphics.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
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
                onWeightChanged(true);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if (this.dragging) {
                this.dragging = false;
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
