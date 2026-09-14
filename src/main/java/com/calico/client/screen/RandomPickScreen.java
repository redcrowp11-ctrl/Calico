package com.calico.client.screen;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Simple dialog: ask for integer count X for random biome pick.
 */
@OnlyIn(Dist.CLIENT)
public class RandomPickScreen extends Screen {
    private final Screen parent;
    private final int maxCount;
    private final IntConsumer onConfirm;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private EditBox countBox;

    public RandomPickScreen(Screen parent, int maxCount, IntConsumer onConfirm) {
        super(Component.translatable("calico.screen.create.random.title"));
        this.parent = parent;
        this.maxCount = Math.max(0, maxCount);
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(8));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        header.addChild(new StringWidget(
                Component.translatable("calico.screen.create.random.hint", this.maxCount), this.font));

        this.countBox = new EditBox(this.font, 0, 0, 100, 20,
                Component.translatable("calico.screen.create.random.field"));
        this.countBox.setValue(Integer.toString(Math.min(5, this.maxCount)));
        this.countBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        this.layout.addToContents(this.countBox);

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> confirm()).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void confirm() {
        int value = 0;
        try {
            value = Integer.parseInt(this.countBox.getValue().trim());
        } catch (NumberFormatException ignored) {
            value = 0;
        }
        value = Math.max(0, Math.min(value, this.maxCount));
        this.onConfirm.accept(value);
        this.onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }
}
