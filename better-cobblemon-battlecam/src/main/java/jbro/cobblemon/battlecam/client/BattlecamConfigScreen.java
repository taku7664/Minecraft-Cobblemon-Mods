package jbro.cobblemon.battlecam.client;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import jbro.cobblemon.battlecam.BattlecamBattleType;
import jbro.cobblemon.battlecam.BattlecamConfig;
import jbro.cobblemon.battlecam.BattlecamConfigStore;
import jbro.cobblemon.battlecam.BattlecamController;
import jbro.cobblemon.battlecam.BattlecamMode;
import jbro.cobblemon.battlecam.BetterCobblemonBattlecamClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class BattlecamConfigScreen extends Screen {
    private static final int ROW_SPACING = 30;
    private static final String KEY = "screen.better_cobblemon_battlecam.";

    private final Screen parent;
    private final Map<BattlecamBattleType, Boolean> enabled = new EnumMap<>(BattlecamBattleType.class);
    private final Map<BattlecamBattleType, BattlecamMode> modes = new EnumMap<>(BattlecamBattleType.class);
    private Text status = Text.empty();
    private int statusColor = 0xFF70E090;

    BattlecamConfigScreen(Screen parent) {
        super(Text.translatable(KEY + "title"));
        this.parent = parent;
        loadDraft(BattlecamConfigStore.current());
    }

    @Override
    protected void init() {
        clearChildren();
        int center = width / 2;
        int top = Math.max(48, height / 2 - 70);
        int index = 0;
        for (BattlecamBattleType type : BattlecamBattleType.values()) {
            int y = top + index * ROW_SPACING;
            ButtonWidget enabledButton = ButtonWidget.builder(
                enabledText(type),
                button -> {
                    enabled.put(type, !enabled.get(type));
                    button.setMessage(enabledText(type));
                }
            ).dimensions(center - 45, y, 100, 20).build();
            ButtonWidget modeButton = ButtonWidget.builder(
                modeText(type),
                button -> {
                    modes.put(type, modes.get(type).next());
                    button.setMessage(modeText(type));
                }
            ).dimensions(center + 60, y, 110, 20).build();
            addDrawableChild(enabledButton);
            addDrawableChild(modeButton);
            index++;
        }

        int bottom = top + BattlecamBattleType.values().length * ROW_SPACING + 8;
        addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "defaults"), button -> resetDefaults())
            .dimensions(center - 170, bottom, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "save"), button -> save())
            .dimensions(center - 52, bottom, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable(KEY + "cancel"), button -> close())
            .dimensions(center + 65, bottom, 105, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int center = width / 2;
        int top = Math.max(48, height / 2 - 70);
        context.drawCenteredTextWithShadow(textRenderer, title, center, 18, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable(KEY + "battle_type"), center - 170, top - 18, 0xFFA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.translatable(KEY + "enabled_header"), center - 45, top - 18, 0xFFA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.translatable(KEY + "mode_header"), center + 60, top - 18, 0xFFA0A0A0);
        int index = 0;
        for (BattlecamBattleType type : BattlecamBattleType.values()) {
            int y = top + index * ROW_SPACING + 6;
            context.drawTextWithShadow(textRenderer, typeText(type), center - 170, y, 0xFFFFFFFF);
            index++;
        }
        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, status, center, top + 122, statusColor);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void resetDefaults() {
        loadDraft(BattlecamConfig.defaults());
        status = Text.empty();
        init();
    }

    private void save() {
        BattlecamConfig config = draft();
        try {
            BattlecamConfigStore.replace(config);
            BattlecamController.applyConfig(config);
            close();
        } catch (IOException exception) {
            BetterCobblemonBattlecamClient.LOGGER.error("Could not save Battlecam config", exception);
            status = Text.translatable(KEY + "save_failed", safeMessage(exception));
            statusColor = 0xFFFF7070;
        }
    }

    private void loadDraft(BattlecamConfig config) {
        for (BattlecamBattleType type : BattlecamBattleType.values()) {
            enabled.put(type, config.enabled(type));
            modes.put(type, config.defaultMode(type));
        }
    }

    private BattlecamConfig draft() {
        return new BattlecamConfig(
            enabled.get(BattlecamBattleType.WILD), modes.get(BattlecamBattleType.WILD),
            enabled.get(BattlecamBattleType.PVE), modes.get(BattlecamBattleType.PVE),
            enabled.get(BattlecamBattleType.PVP), modes.get(BattlecamBattleType.PVP)
        );
    }

    private Text enabledText(BattlecamBattleType type) {
        return Text.translatable(KEY + (enabled.get(type) ? "enabled" : "disabled"));
    }

    private Text modeText(BattlecamBattleType type) {
        return Text.translatable(KEY + "mode", modes.get(type).name());
    }

    private static Text typeText(BattlecamBattleType type) {
        return Text.translatable(KEY + "type." + type.name().toLowerCase());
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
