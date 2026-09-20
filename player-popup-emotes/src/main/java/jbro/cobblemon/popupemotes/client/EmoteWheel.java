package jbro.cobblemon.popupemotes.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

final class EmoteWheel {
    private static boolean open;
    private static boolean keyWasDown;
    private static int selectedIndex = -1;
    private static int pageIndex;
    private static boolean leftWasDown;
    private static boolean rightWasDown;

    private EmoteWheel() {
    }

    static void tick(Minecraft client, KeyMapping key) {
        boolean keyDown = key.isDown();
        if (keyDown && !keyWasDown && client.player != null && client.screen == null && EmoteWheelPages.pageCount() > 0) {
            open = true;
            selectedIndex = -1;
            client.mouseHandler.releaseMouse();
        }

        if (open) {
            long window = client.getWindow().getWindow();
            boolean pauseScreenOpened = client.screen instanceof PauseScreen;
            boolean escapeCancel = pauseScreenOpened || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ESCAPE);
            boolean rightMouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (EmoteWheelCancelInput.requested(escapeCancel, rightMouseDown)) {
                close(client);
                if (pauseScreenOpened) {
                    client.setScreen(null);
                }
            }
        }

        if (!keyDown && keyWasDown && open) {
            int selection = selectedIndex;
            close(client);
            var page = currentPage();
            if (selection >= 0 && selection < page.size() && client.player != null) {
                PlayerPopupEmotesClient.send(page.get(selection).reference());
            }
        }
        if (open) {
            handlePageKeys(client);
        }
        if (open && (client.player == null || client.screen != null)) {
            close(client);
        }
        keyWasDown = keyDown;
    }

    static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        if (!open) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int centerX = width / 2;
        int centerY = height / 2;
        updateSelection(client, centerX, centerY, width, height);

        var emotes = currentPage();
        EmoteWheelRenderer.render(graphics, centerX, centerY, selectedIndex, emotes);

        if (selectedIndex >= 0 && selectedIndex < emotes.size()) {
            WheelEmote selected = emotes.get(selectedIndex);
            graphics.drawCenteredString(
                client.font,
                selected.label(),
                centerX,
                centerY + 108,
                0xFFFFFFFF
            );
        }
        int pages = EmoteWheelPages.pageCount();
        if (pages > 1) {
            graphics.drawCenteredString(client.font, "←  " + (pageIndex + 1) + " / " + pages + "  →", centerX, centerY + 121, 0xFFB8C3D9);
        }
    }

    static void reset(Minecraft client) {
        boolean wasOpen = open;
        open = false;
        keyWasDown = false;
        selectedIndex = -1;
        leftWasDown = false;
        rightWasDown = false;
        if (wasOpen && client.screen == null) {
            client.mouseHandler.grabMouse();
        }
    }

    private static void updateSelection(Minecraft client, int centerX, int centerY, int guiWidth, int guiHeight) {
        double mouseX = client.mouseHandler.xpos() * guiWidth / client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * guiHeight / client.getWindow().getScreenHeight();
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        selectedIndex = EmoteWheelSelection.index(dx, dy, currentPage().size());
    }

    private static List<WheelEmote> currentPage() {
        int count = EmoteWheelPages.pageCount();
        if (count == 0) {
            pageIndex = 0;
            return List.of();
        }
        pageIndex = Math.min(pageIndex, count - 1);
        return EmoteWheelPages.page(pageIndex);
    }

    private static void handlePageKeys(Minecraft client) {
        long window = client.getWindow().getWindow();
        boolean leftDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT);
        boolean rightDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT);
        int count = EmoteWheelPages.pageCount();
        if (leftDown && !leftWasDown && count > 1) {
            pageIndex = Math.floorMod(pageIndex - 1, count);
            selectedIndex = -1;
        }
        if (rightDown && !rightWasDown && count > 1) {
            pageIndex = Math.floorMod(pageIndex + 1, count);
            selectedIndex = -1;
        }
        leftWasDown = leftDown;
        rightWasDown = rightDown;
    }

    private static void close(Minecraft client) {
        open = false;
        selectedIndex = -1;
        if (client.screen == null) {
            client.mouseHandler.grabMouse();
        }
    }
}
