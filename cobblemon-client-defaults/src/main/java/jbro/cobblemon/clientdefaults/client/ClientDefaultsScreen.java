package jbro.cobblemon.clientdefaults.client;

import java.io.IOException;
import jbro.cobblemon.clientdefaults.ClientDefaults;
import jbro.cobblemon.clientdefaults.ClientDefaultsPreLaunch;
import jbro.cobblemon.clientdefaults.KeybindingDefaults;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ClientDefaultsScreen extends Screen {
    private static final String KEY = "screen.cobblemon_client_defaults.";
    private final Screen parent;
    private ClientDefaults.ApplyMode mode = ClientDefaults.ApplyMode.ALWAYS;
    private ClientDefaults.ApplyMode keybindingsMode = ClientDefaults.ApplyMode.ONCE;
    private final boolean clcInstalled;
    private boolean editable = true;
    private Component status = Component.empty();

    ClientDefaultsScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        clcInstalled = FabricLoader.getInstance().isModLoaded("cobbled_level_control");
        if (!clcInstalled) status = Component.translatable(KEY + "clc_missing");
        try {
            mode = ClientDefaults.mode(FabricLoader.getInstance().getConfigDir());
            keybindingsMode = KeybindingDefaults.mode(FabricLoader.getInstance().getConfigDir());
        } catch (IOException exception) {
            editable = false;
            status = Component.translatable(KEY + "read_failed");
            ClientDefaultsPreLaunch.LOGGER.error("Could not read client defaults settings", exception);
        }
    }

    @Override
    protected void init() {
        int y = rowY();
        Button toggle = addRenderableWidget(Button.builder(hudLabel(), button -> {
            mode = mode == ClientDefaults.ApplyMode.ALWAYS
                ? ClientDefaults.ApplyMode.ONCE : ClientDefaults.ApplyMode.ALWAYS;
            button.setMessage(hudLabel());
        }).bounds(width / 2 - 130, y, 260, 20).build());
        toggle.active = editable && clcInstalled;
        Button keys = addRenderableWidget(Button.builder(keybindingsLabel(), button -> {
            keybindingsMode = keybindingsMode == ClientDefaults.ApplyMode.ALWAYS
                ? ClientDefaults.ApplyMode.ONCE : ClientDefaults.ApplyMode.ALWAYS;
            button.setMessage(keybindingsLabel());
        }).bounds(width / 2 - 130, y + 25, 260, 20)
            .tooltip(Tooltip.create(Component.translatable(KEY + "keybindings_help"))).build());
        keys.active = editable;
        Button save = addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), button -> {
            try {
                ClientDefaults.saveModes(FabricLoader.getInstance().getConfigDir(), mode, keybindingsMode);
                status = Component.translatable(KEY + "saved");
            } catch (IOException exception) {
                status = Component.translatable(KEY + "save_failed");
                ClientDefaultsPreLaunch.LOGGER.error("Could not save client defaults settings", exception);
            }
        }).bounds(width / 2 - 130, y + 60, 125, 20).build());
        save.active = editable;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
            .bounds(width / 2 + 5, y + 60, 125, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        MultiLineLabel.create(font, Component.translatable(KEY + "description"), Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, 44, 12, 0xAAAAAA);
        MultiLineLabel.create(font, status, Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, rowY() + 92, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private Component hudLabel() {
        return Component.translatable(KEY + "clc_hud", Component.translatable(KEY + "mode." + mode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private Component keybindingsLabel() {
        return Component.translatable(KEY + "keybindings", Component.translatable(KEY + "mode." + keybindingsMode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private int rowY() { return Math.max(100, height / 2 - 35); }
}
