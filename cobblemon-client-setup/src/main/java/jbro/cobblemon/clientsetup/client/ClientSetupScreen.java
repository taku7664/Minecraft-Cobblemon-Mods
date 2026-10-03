package jbro.cobblemon.clientsetup.client;

import java.io.IOException;
import jbro.cobblemon.clientsetup.ClientSetup;
import jbro.cobblemon.clientsetup.ClientSetupPreLaunch;
import jbro.cobblemon.clientsetup.KeybindingSetup;
import jbro.cobblemon.clientsetup.XaeroSetup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ClientSetupScreen extends Screen {
    private static final String KEY = "screen.cobblemon_client_setup.";
    private final Screen parent;
    private ClientSetup.ApplyMode mode = ClientSetup.ApplyMode.ALWAYS;
    private ClientSetup.ApplyMode keybindingsMode = ClientSetup.ApplyMode.ONCE;
    private ClientSetup.ApplyMode xaeroMode = ClientSetup.ApplyMode.ALWAYS;
    private final boolean clcInstalled;
    private final boolean xaeroInstalled;
    private boolean editable = true;
    private Component status = Component.empty();

    ClientSetupScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        clcInstalled = FabricLoader.getInstance().isModLoaded("cobbled_level_control");
        xaeroInstalled = FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        if (!clcInstalled) status = Component.translatable(KEY + "clc_missing");
        try {
            mode = ClientSetup.mode(FabricLoader.getInstance().getConfigDir());
            keybindingsMode = KeybindingSetup.mode(FabricLoader.getInstance().getConfigDir());
            xaeroMode = XaeroSetup.mode(FabricLoader.getInstance().getConfigDir());
        } catch (IOException exception) {
            editable = false;
            status = Component.translatable(KEY + "read_failed");
            ClientSetupPreLaunch.LOGGER.error("Could not read client setup settings", exception);
        }
    }

    @Override
    protected void init() {
        int y = rowY();
        Button toggle = addRenderableWidget(Button.builder(hudLabel(), button -> {
            mode = mode == ClientSetup.ApplyMode.ALWAYS
                ? ClientSetup.ApplyMode.ONCE : ClientSetup.ApplyMode.ALWAYS;
            button.setMessage(hudLabel());
        }).bounds(width / 2 - 130, y, 260, 20).build());
        toggle.active = editable && clcInstalled;
        Button keys = addRenderableWidget(Button.builder(keybindingsLabel(), button -> {
            keybindingsMode = keybindingsMode == ClientSetup.ApplyMode.ALWAYS
                ? ClientSetup.ApplyMode.ONCE : ClientSetup.ApplyMode.ALWAYS;
            button.setMessage(keybindingsLabel());
        }).bounds(width / 2 - 130, y + 25, 260, 20)
            .tooltip(Tooltip.create(Component.translatable(KEY + "keybindings_help"))).build());
        keys.active = editable;
        Button maps = addRenderableWidget(Button.builder(xaeroLabel(), button -> {
            xaeroMode = xaeroMode == ClientSetup.ApplyMode.ALWAYS
                ? ClientSetup.ApplyMode.ONCE : ClientSetup.ApplyMode.ALWAYS;
            button.setMessage(xaeroLabel());
        }).bounds(width / 2 - 130, y + 50, 260, 20)
            .tooltip(Tooltip.create(Component.translatable(KEY + "xaero_help"))).build());
        maps.active = editable && xaeroInstalled;
        Button save = addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), button -> {
            try {
                ClientSetup.saveModes(FabricLoader.getInstance().getConfigDir(), mode, keybindingsMode, xaeroMode);
                status = Component.translatable(KEY + "saved");
            } catch (IOException exception) {
                status = Component.translatable(KEY + "save_failed");
                ClientSetupPreLaunch.LOGGER.error("Could not save client setup settings", exception);
            }
        }).bounds(width / 2 - 130, y + 85, 125, 20).build());
        save.active = editable;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
            .bounds(width / 2 + 5, y + 85, 125, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        MultiLineLabel.create(font, Component.translatable(KEY + "description"), Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, 44, 12, 0xAAAAAA);
        MultiLineLabel.create(font, status, Math.min(360, width - 40))
            .renderCentered(graphics, width / 2, rowY() + 117, 12, 0xFFFFFF);
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

    private Component xaeroLabel() {
        return Component.translatable(KEY + "xaero", Component.translatable(KEY + "mode." + xaeroMode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private int rowY() { return Math.max(100, height / 2 - 48); }
}
