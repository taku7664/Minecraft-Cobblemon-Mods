package jbro.cobblemon.battlespeed.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown instead of the settings when Cloth Config is missing; the command still works then. */
final class BattleSpeedMissingClothScreen extends Screen {
    private final Screen parent;

    BattleSpeedMissingClothScreen(Screen parent) {
        super(Component.translatable("cobblemon_battle_speed.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
            .bounds((width - 200) / 2, height / 2 + 24, 200, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 30, 0xFFFFFF);
        graphics.drawCenteredString(
            font, Component.translatable("cobblemon_battle_speed.config.cloth_missing"), width / 2, height / 2 - 10, 0xFFAAAA
        );
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
