package jbro.cobblemon.bettermusic.client;

import java.nio.file.Path;
import jbro.cobblemon.bettermusic.BetterCobblemonMusicClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class BetterMusicConfigProblemScreen extends Screen {
    private final Screen parent;
    private final Path configFile;
    private final Component problem;

    private BetterMusicConfigProblemScreen(Screen parent, Path configFile, Component problem) {
        super(Component.translatable("better_cobblemon_music.config.title"));
        this.parent = parent;
        this.configFile = configFile;
        this.problem = problem;
    }

    static BetterMusicConfigProblemScreen loadFailure(Screen parent, Path configFile, String problem) {
        return new BetterMusicConfigProblemScreen(
            parent,
            configFile,
            Component.translatable("better_cobblemon_music.config.load_failed", problem)
        );
    }

    static BetterMusicConfigProblemScreen missingClothConfig(Screen parent) {
        Path configFile = FabricLoader.getInstance().getConfigDir()
            .resolve(BetterCobblemonMusicClient.MOD_ID)
            .resolve("settings.json")
            .toAbsolutePath()
            .normalize();
        return new BetterMusicConfigProblemScreen(
            parent,
            configFile,
            Component.translatable("better_cobblemon_music.config.cloth_missing")
        );
    }

    @Override
    protected void init() {
        int left = (width - 220) / 2;
        addRenderableWidget(Button.builder(
            Component.translatable("better_cobblemon_music.config.open_folder"),
            ignored -> Util.getPlatform().openFile(configFile.getParent().toFile())
        ).bounds(left, height / 2 + 16, 220, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("better_cobblemon_music.config.done"),
            ignored -> onClose()
        ).bounds(left, height / 2 + 40, 220, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 54, 0xFFFFFFFF);
        int textY = height / 2 - 32;
        for (var line : font.split(problem, Math.min(width - 32, 420))) {
            graphics.drawCenteredString(font, line, width / 2, textY, 0xFFFF7070);
            textY += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
