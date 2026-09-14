package com.calico.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.calico.client.data.BiomeCatalog;
import com.calico.client.data.BiomeEntry;
import com.calico.client.data.BiomeFilterState;
import com.calico.client.data.BiomePresets;
import com.calico.client.data.BiomeSelectionPersistence;
import com.calico.client.data.BiomeSelectionState;
import com.calico.client.data.BiomeTagClassifier;
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

    private String pendingSearch = "";
    private long searchDirtyAtMs = 0L;
    private boolean searchDirty = false;

    private int contentTop;
    private int contentBottom;
    private int detailLeft;

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

        int margin = 4;
        this.contentTop = 82;
        this.contentBottom = this.height - 52;
        this.detailLeft = this.width - 160;

        // Title area is drawn in render(); selected-count badge too.

        // Dimension tabs
        int tabY = 22;
        int tabX = margin;
        tabX += addDimTab(tabX, tabY, BiomeDimension.OVERWORLD, "calico.screen.create.tab.overworld") + 2;
        tabX += addDimTab(tabX, tabY, BiomeDimension.NETHER, "calico.screen.create.tab.nether") + 2;
        addDimTab(tabX, tabY, BiomeDimension.END, "calico.screen.create.tab.end");

        // Search (debounced in tick)
        this.searchBox = new EditBox(this.font, margin, 42, 140, 18,
                Component.translatable("calico.screen.create.search"));
        this.searchBox.setHint(Component.translatable("calico.screen.create.search.hint"));
        this.searchBox.setResponder(value -> {
            this.pendingSearch = value;
            this.searchDirtyAtMs = System.currentTimeMillis();
            this.searchDirty = true;
        });
        this.searchBox.setValue(this.filters.searchQuery());
        addRenderableWidget(this.searchBox);

        // Vanilla / Modded cycle
        addRenderableWidget(CycleButton.<BiomeFilterState.VanillaModdedFilter>builder(v -> Component.translatable(
                        switch (v) {
                            case ALL -> "calico.screen.create.filter.all";
                            case VANILLA_ONLY -> "calico.screen.create.filter.vanilla";
                            case MODDED_ONLY -> "calico.screen.create.filter.modded";
                        }))
                .withValues(BiomeFilterState.VanillaModdedFilter.values())
                .withInitialValue(this.filters.vanillaModded())
                .create(148, 42, 100, 18, Component.translatable("calico.screen.create.filter.source"),
                        (btn, value) -> {
                            this.filters.setVanillaModded(value);
                            refreshGrid();
                        }));

        // Tag chips (compact)
        int chipX = 252;
        int chipY = 42;
        chipX += addChip(chipX, chipY, 36, Component.translatable("calico.screen.create.chip.hot"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.HOT);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.HOT)) + 1;
        chipX += addChip(chipX, chipY, 40, Component.translatable("calico.screen.create.chip.temp"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.TEMPERATE);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.TEMPERATE)) + 1;
        chipX += addChip(chipX, chipY, 36, Component.translatable("calico.screen.create.chip.cold"),
                () -> {
                    this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.COLD);
                    refreshGrid();
                }, () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.COLD)) + 1;
        chipX += addChip(chipX, chipY, 32, Component.translatable("calico.screen.create.chip.dry"),
                () -> {
                    this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.DRY);
                    refreshGrid();
                }, () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.DRY)) + 1;
        chipX += addChip(chipX, chipY, 32, Component.translatable("calico.screen.create.chip.humid"),
                () -> {
                    this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.HUMID);
                    refreshGrid();
                }, () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.HUMID)) + 1;
        chipX += addChip(chipX, chipY, 40, Component.translatable("calico.screen.create.chip.precip"),
                () -> {
                    this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS);
                    refreshGrid();
                }, () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS)) + 1;
        addChip(chipX, chipY, 48, Component.translatable("calico.screen.create.chip.no_precip"),
                () -> {
                    this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS_NOT);
                    refreshGrid();
                }, () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS_NOT));

        // QoL row
        int qY = 62;
        int qX = margin;
        qX += addSmallButton(qX, qY, 54, "calico.screen.create.qol.select_all", this::selectAllVisible) + 1;
        qX += addSmallButton(qX, qY, 40, "calico.screen.create.qol.clear", () -> {
            this.selection.clear();
            onSelectionChanged();
        }) + 1;
        qX += addSmallButton(qX, qY, 44, "calico.screen.create.qol.invert", this::invertVisible) + 1;
        qX += addSmallButton(qX, qY, 50, "calico.screen.create.preset.deserts", () -> {
            BiomePresets.applyDeserts(this.catalog, this.selection);
            onSelectionChanged();
        }) + 1;
        qX += addSmallButton(qX, qY, 40, "calico.screen.create.preset.cold", () -> {
            BiomePresets.applyCold(this.catalog, this.selection);
            onSelectionChanged();
        }) + 1;
        qX += addSmallButton(qX, qY, 58, "calico.screen.create.preset.no_oceans", () -> {
            BiomePresets.applyNoOceans(this.catalog, this.selection);
            onSelectionChanged();
        }) + 1;
        qX += addSmallButton(qX, qY, 52, "calico.screen.create.qol.random", this::openRandomPick) + 1;
        qX += addSmallButton(qX, qY, 48, "calico.screen.create.qol.export", this::doExport) + 1;
        addSmallButton(qX, qY, 48, "calico.screen.create.qol.import", this::doImport);

        // Grid
        int gridWidth = Math.max(100, this.detailLeft - margin - 4);
        int gridHeight = Math.max(40, this.contentBottom - this.contentTop);
        this.grid = new BiomeTileGridWidget(
                this.minecraft, gridWidth, gridHeight, this.contentTop, this.selection, entry -> onSelectionChanged());
        this.grid.setX(margin);
        addRenderableWidget(this.grid);

        // Detail pane: frequency slider + reset
        this.weightSlider = new WeightSlider(this.detailLeft, this.contentTop + 24, 150, 20);
        addRenderableWidget(this.weightSlider);
        this.resetWeightButton = Button.builder(Component.translatable("calico.screen.create.freq.reset"), b -> {
                    ResourceLocation focused = this.selection.focused();
                    if (focused != null) {
                        BiomeEntry entry = this.catalog.get(focused);
                        if (entry != null) {
                            this.selection.resetWeight(entry);
                            onSelectionChanged();
                        }
                    }
                }).bounds(this.detailLeft, this.contentTop + 48, 150, 20).build();
        addRenderableWidget(this.resetWeightButton);

        // Preview placeholder (non-blocking)
        // drawn in render()

        // Footer
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

    private int addDimTab(int x, int y, BiomeDimension dim, String key) {
        Button btn = Button.builder(Component.translatable(key), b -> {
            this.filters.setDimensionTab(dim);
            refreshGrid();
        }).bounds(x, y, 70, 18).build();
        addRenderableWidget(btn);
        return 70;
    }

    private int addChip(int x, int y, int w, Component label, Runnable action, java.util.function.BooleanSupplier active) {
        // Prefix asterisk when active so chip state is visible without a custom Button subclass.
        Button btn = Button.builder(label, b -> {
            action.run();
            b.setMessage(active.getAsBoolean()
                    ? Component.literal("*").append(label)
                    : label);
        }).bounds(x, y, w, 18).build();
        if (active.getAsBoolean()) {
            btn.setMessage(Component.literal("*").append(label));
        }
        addRenderableWidget(btn);
        return w;
    }

    private int addSmallButton(int x, int y, int w, String key, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(key), b -> action.run())
                .bounds(x, y, w, 16).build());
        return w;
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
        this.selectedCountLabel = Component.translatable(
                "calico.screen.create.selected", this.selection.selectedCount());

        CalicoWorldGenConfig config = this.selection.toConfig();
        CalicoConfigValidation.Result result = CalicoConfigValidation.validateForCreate(config);
        this.createButton.active = result.valid();
        this.gateMessage = result.valid()
                ? Component.empty()
                : Component.translatable("calico.screen.create.gate");

        ResourceLocation focused = this.selection.focused();
        boolean hasFocusedSelected = focused != null && this.selection.isSelected(focused);
        this.weightSlider.active = hasFocusedSelected;
        this.resetWeightButton.active = hasFocusedSelected;
        if (hasFocusedSelected) {
            double w = this.selection.weightOf(focused);
            this.weightSlider.setWeight(w);
            this.relativePercentLabel = Component.translatable(
                    "calico.screen.create.freq.percent",
                    String.format("%.1f", this.selection.relativePercent(focused)));
        } else {
            this.relativePercentLabel = Component.translatable("calico.screen.create.freq.percent_none");
        }

        // Refresh weight badges on tiles
        refreshGrid();
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

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
        graphics.drawString(this.font, this.selectedCountLabel, this.width - 8 - this.font.width(this.selectedCountLabel), 8, 0xFFEE88);

        // Detail labels
        graphics.drawString(this.font, Component.translatable("calico.screen.create.freq.title"),
                this.detailLeft, this.contentTop + 4, 0xFFFFFF);
        graphics.drawString(this.font, this.relativePercentLabel, this.detailLeft, this.contentTop + 72, 0xAAAAAA);

        // Preview placeholder
        int previewTop = this.contentTop + 96;
        graphics.fill(this.detailLeft, previewTop, this.detailLeft + 150, previewTop + 80, 0x40000000);
        graphics.drawCenteredString(this.font,
                Component.translatable("calico.screen.create.preview.placeholder"),
                this.detailLeft + 75, previewTop + 36, 0x888888);

        // Fun stub note (hidden tab — coming soon only)
        graphics.drawString(this.font, Component.translatable("calico.fun.coming_soon"),
                this.detailLeft, this.contentBottom - 12, 0x666666);

        if (!this.unavailableNote.getString().isEmpty()) {
            graphics.drawString(this.font, this.unavailableNote, 8, this.height - 44, 0xFFAA66);
        }
        if (!this.gateMessage.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.gateMessage, this.width / 2, this.height - 44, 0xFF6666);
        }
        if (!this.statusMessage.getString().isEmpty()) {
            graphics.drawString(this.font, this.statusMessage, 8, this.height - 56, 0x88FF88);
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
                onSelectionChanged();
            }
        }
    }
}
