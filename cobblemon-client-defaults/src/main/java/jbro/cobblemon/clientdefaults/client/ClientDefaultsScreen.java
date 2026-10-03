package jbro.cobblemon.clientdefaults.client;

import java.io.IOException;
import jbro.cobblemon.clientdefaults.ClientDefaults;
import jbro.cobblemon.clientdefaults.ClientDefaultsPreLaunch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ClientDefaultsScreen extends Screen {
    private static final String KEY = "screen.cobblemon_client_defaults.";
    private final Screen parent;
    private ClientDefaults.ApplyMode mode = ClientDefaults.ApplyMode.ALWAYS;
    private boolean editable;
    private Component status = Component.empty();

    ClientDefaultsScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        editable = FabricLoader.getInstance().isModLoaded("cobbled_level_control");
        if (!editable) {
            status = Component.translatable(KEY + "clc_missing");
            return;
        }
        try {
            mode = ClientDefaults.mode(FabricLoader.getInstance().getConfigDir());
        } catch (IOException exception) {
            editable = false;
            status = Component.translatable(KEY + "read_failed");
            ClientDefaultsPreLaunch.LOGGER.error("Could not read CLC HUD setting", exception);
        }
    }

    @Override
    protected void init() {
        int y = Math.max(100, height / 2 - 20);
        Button toggle = addRenderableWidget(Button.builder(hudLabel(), button -> {
            mode = mode == ClientDefaults.ApplyMode.ALWAYS
                ? ClientDefaults.ApplyMode.ONCE : ClientDefaults.ApplyMode.ALWAYS;
            button.setMessage(hudLabel());
        }).bounds(width / 2 - 130, y, 260, 20).build());
        toggle.active = editable;
        Button save = addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), button -> {
            try {
                ClientDefaults.saveMode(FabricLoader.getInstance().getConfigDir(), mode);
                status = Component.translatable(KEY + "saved");
            } catch (IOException exception) {
                status = Component.translatable(KEY + "save_failed");
                ClientDefaultsPreLaunch.LOGGER.error("Could not save CLC HUD setting", exception);
            }
        }).bounds(width / 2 - 130, y + 30, 125, 20).build());
        save.active = editable;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
            .bounds(width / 2 + 5, y + 30, 125, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        MultiLineLabel.create(font, Component.translatable(KEY + "description"), Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, 44, 12, 0xAAAAAA);
        MultiLineLabel.create(font, status, Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, Math.max(100, height / 2 - 20) + 62, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private Component hudLabel() {
        return Component.translatable(KEY + "clc_hud", Component.translatable(KEY + "mode." + mode.name().toLowerCase(java.util.Locale.ROOT)));
    }
}
