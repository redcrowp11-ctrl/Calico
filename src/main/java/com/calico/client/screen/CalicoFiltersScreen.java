package com.calico.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.calico.client.data.BiomeCatalog;
import com.calico.client.data.BiomeEntry;
import com.calico.client.data.BiomeFilterState;
import com.calico.client.data.BiomePresets;
import com.calico.client.data.BiomeSelectionPersistence;
import com.calico.client.data.BiomeSelectionState;
import com.calico.client.data.BiomeTagClassifier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Secondary screen for climate chips, QoL actions, presets, and export/import.
 * Keeps the main Customize screen clean like vanilla buffet.
 */
@OnlyIn(Dist.CLIENT)
public class CalicoFiltersScreen extends Screen {
    private static final int BTN_H = 20;
    private static final int GAP = 6;
    private static final int MARGIN = 12;

    private final CalicoCreateWorldScreen parent;
    private final BiomeCatalog catalog;
    private final BiomeSelectionState selection;
    private final BiomeFilterState filters;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    private final List<SectionLabel> sectionLabels = new ArrayList<>();
    private Component statusMessage = Component.empty();

    public CalicoFiltersScreen(
            CalicoCreateWorldScreen parent,
            BiomeCatalog catalog,
            BiomeSelectionState selection,
            BiomeFilterState filters) {
        super(Component.translatable("calico.screen.filters.title"));
        this.parent = parent;
        this.catalog = catalog;
        this.selection = selection;
        this.filters = filters;
    }

    @Override
    protected void init() {
        this.sectionLabels.clear();

        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();

        int x = MARGIN;
        int maxX = this.width - MARGIN;
        int y = this.layout.getHeaderHeight() + 10;

        addRenderableWidget(CycleButton.<BiomeFilterState.VanillaModdedFilter>builder(v -> Component.translatable(
                        switch (v) {
                            case ALL -> "calico.screen.create.filter.all";
                            case VANILLA_ONLY -> "calico.screen.create.filter.vanilla";
                            case MODDED_ONLY -> "calico.screen.create.filter.modded";
                        }))
                .withValues(BiomeFilterState.VanillaModdedFilter.values())
                .withInitialValue(this.filters.vanillaModded())
                .create(x, y, 180, BTN_H, Component.translatable("calico.screen.create.filter.source"),
                        (btn, value) -> this.filters.setVanillaModded(value)));
        y += BTN_H + GAP + 6;

        y = addSection(y, "calico.screen.filters.section.climate");
        y = addChipRow(x, y, maxX, List.of(
                chip(Component.translatable("calico.screen.create.chip.hot"),
                        () -> this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.HOT),
                        () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.HOT)),
                chip(Component.translatable("calico.screen.create.chip.temp"),
                        () -> this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.TEMPERATE),
                        () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.TEMPERATE)),
                chip(Component.translatable("calico.screen.create.chip.cold"),
                        () -> this.filters.toggleTemperature(BiomeTagClassifier.TemperatureBucket.COLD),
                        () -> this.filters.temperatures().contains(BiomeTagClassifier.TemperatureBucket.COLD)),
                chip(Component.translatable("calico.screen.create.chip.dry"),
                        () -> this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.DRY),
                        () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.DRY)),
                chip(Component.translatable("calico.screen.create.chip.humid"),
                        () -> this.filters.toggleHumidity(BiomeTagClassifier.HumidityBucket.HUMID),
                        () -> this.filters.humidities().contains(BiomeTagClassifier.HumidityBucket.HUMID)),
                chip(Component.translatable("calico.screen.create.chip.precip"),
                        () -> this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS),
                        () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS)),
                chip(Component.translatable("calico.screen.create.chip.no_precip"),
                        () -> this.filters.togglePrecip(BiomeFilterState.PrecipFilter.HAS_NOT),
                        () -> this.filters.precipitations().contains(BiomeFilterState.PrecipFilter.HAS_NOT))
        ));
        y += 4;

        y = addSection(y, "calico.screen.filters.section.qol");
        y = addActionRow(x, y, maxX, List.of(
                action("calico.screen.create.qol.select_all", this::selectAllVisible),
                action("calico.screen.create.qol.clear", () -> {
                    this.selection.clear();
                    this.parent.notifySelectionChangedFromFilters();
                }),
                action("calico.screen.create.qol.invert", this::invertVisible),
                action("calico.screen.create.qol.random", this::openRandomPick)
        ));
        y += 4;

        y = addSection(y, "calico.screen.filters.section.presets");
        y = addActionRow(x, y, maxX, List.of(
                action("calico.screen.create.preset.deserts", () -> {
                    BiomePresets.applyDeserts(this.catalog, this.selection);
                    this.parent.notifySelectionChangedFromFilters();
                }),
                action("calico.screen.create.preset.cold", () -> {
                    BiomePresets.applyCold(this.catalog, this.selection);
                    this.parent.notifySelectionChangedFromFilters();
                }),
                action("calico.screen.create.preset.no_oceans", () -> {
                    BiomePresets.applyNoOceans(this.catalog, this.selection);
                    this.parent.notifySelectionChangedFromFilters();
                })
        ));
        y += 4;

        y = addSection(y, "calico.screen.filters.section.data");
        addActionRow(x, y, maxX, List.of(
                action("calico.screen.create.qol.export", this::doExport),
                action("calico.screen.create.qol.import", this::doImport)
        ));
    }

    private int addSection(int y, String key) {
        this.sectionLabels.add(new SectionLabel(y, Component.translatable(key)));
        return y + 12;
    }

    private record SectionLabel(int y, Component text) {
    }

    private record ChipSpec(Component label, Runnable action, BooleanSupplier active) {
    }

    private record ActionSpec(String key, Runnable action) {
    }

    private ChipSpec chip(Component label, Runnable action, BooleanSupplier active) {
        return new ChipSpec(label, action, active);
    }

    private ActionSpec action(String key, Runnable action) {
        return new ActionSpec(key, action);
    }

    private int addChipRow(int startX, int y, int maxX, List<ChipSpec> chips) {
        int x = startX;
        int rowY = y;
        for (ChipSpec chip : chips) {
            int w = Math.max(56, this.font.width(chip.label()) + 16);
            if (x + w > maxX && x > startX) {
                x = startX;
                rowY += BTN_H + GAP;
            }
            Component msg = chip.active().getAsBoolean()
                    ? Component.literal("✔ ").append(chip.label())
                    : chip.label();
            Button btn = Button.builder(msg, b -> {
                chip.action().run();
                b.setMessage(chip.active().getAsBoolean()
                        ? Component.literal("✔ ").append(chip.label())
                        : chip.label());
            }).bounds(x, rowY, w, BTN_H).build();
            addRenderableWidget(btn);
            x += w + GAP;
        }
        return rowY + BTN_H + GAP;
    }

    private int addActionRow(int startX, int y, int maxX, List<ActionSpec> actions) {
        int x = startX;
        int rowY = y;
        for (ActionSpec spec : actions) {
            Component label = Component.translatable(spec.key());
            int w = Math.max(72, this.font.width(label) + 20);
            if (x + w > maxX && x > startX) {
                x = startX;
                rowY += BTN_H + GAP;
            }
            addRenderableWidget(Button.builder(label, b -> spec.action().run())
                    .bounds(x, rowY, w, BTN_H).build());
            x += w + GAP;
        }
        return rowY + BTN_H + GAP;
    }

    private List<BiomeEntry> visibleBiomes() {
        return this.catalog.filter(this.filters);
    }

    private void selectAllVisible() {
        this.selection.selectAll(visibleBiomes());
        this.parent.notifySelectionChangedFromFilters();
    }

    private void invertVisible() {
        this.selection.invertVisible(new ArrayList<>(visibleBiomes()));
        this.parent.notifySelectionChangedFromFilters();
    }

    private void openRandomPick() {
        List<BiomeEntry> visible = visibleBiomes();
        this.minecraft.setScreen(new RandomPickScreen(this, visible.size(), count -> {
            this.selection.randomPick(visible, count);
            this.parent.notifySelectionChangedFromFilters();
        }));
    }

    private void doExport() {
        ExportImportHelper.exportToClipboard(this.minecraft, this.selection.toConfig());
        this.statusMessage = Component.translatable("calico.screen.create.export.ok");
    }

    private void doImport() {
        var parsed = ExportImportHelper.importFromClipboard(this.minecraft);
        if (parsed.isEmpty()) {
            this.statusMessage = Component.translatable("calico.screen.create.import.fail");
            return;
        }
        this.selection.replaceFromConfig(parsed.get(), this.catalog.presentIds());
        BiomeSelectionPersistence.save(this.minecraft, this.selection.toConfig());
        this.parent.notifySelectionChangedFromFilters();
        this.parent.updateUnavailableNoteFromFilters();
        this.statusMessage = Component.translatable("calico.screen.create.import.ok");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        for (SectionLabel label : this.sectionLabels) {
            graphics.drawString(this.font, label.text(), MARGIN, label.y(), 0xAAAAAA);
        }
        if (!this.statusMessage.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2,
                    this.height - this.layout.getFooterHeight() - 14, 0x88FF88);
        }
    }

    @Override
    public void onClose() {
        this.parent.onFiltersClosed();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }
}
