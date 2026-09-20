package jbro.cobblemon.popupemotes.client.custom;

import jbro.cobblemon.popupemotes.client.CustomEmotesScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public final class CustomEmotesScreenAccess {
    private CustomEmotesScreenAccess() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> addButton(client, screen));
    }

    private static void addButton(net.minecraft.client.Minecraft client, Screen screen) {
        if (!(screen instanceof PauseScreen) && !(screen instanceof TitleScreen)) {
            return;
        }
        var button = Button.builder(
            Component.translatable("screen.player_popup_emotes.my_emotes"),
            ignored -> client.setScreen(new CustomEmotesScreen(screen))
        ).bounds(Math.max(8, screen.width - 112), 8, 104, 20).build();
        Screens.getButtons(screen).add(button);
    }
}
