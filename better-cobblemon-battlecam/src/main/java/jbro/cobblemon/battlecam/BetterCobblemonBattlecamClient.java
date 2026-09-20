package jbro.cobblemon.battlecam;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterCobblemonBattlecamClient implements ClientModInitializer {
    public static final String MOD_ID = "better_cobblemon_battlecam";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private KeyBinding cycleMode;
    private KeyBinding nextShot;
    private KeyBinding previousShot;

    @Override
    public void onInitializeClient() {
        cycleMode = register("cycle_mode", GLFW.GLFW_KEY_F6);
        nextShot = register("next_shot", GLFW.GLFW_KEY_F7);
        previousShot = register("previous_shot", GLFW.GLFW_KEY_F8);

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (!BattlecamController.isActive()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            Text text = Text.translatable(
                "hud.better_cobblemon_battlecam.status",
                BattlecamController.mode().name(),
                BattlecamController.shotNumber(),
                BattlecamController.shotCount(),
                BattlecamController.shotName()
            );
            int width = client.textRenderer.getWidth(text);
            int x = (context.getScaledWindowWidth() - width) / 2;
            context.fill(x - 5, 7, x + width + 5, 22, 0x99000000);
            context.drawTextWithShadow(client.textRenderer, text, x, 10, 0xFFF2D36B);
        });

        LOGGER.info("Better Cobblemon Battlecam initialized for Cobblemon 1.8.1");
    }

    private void tick(MinecraftClient client) {
        while (cycleMode.wasPressed()) {
            BattlecamController.cycleMode(client);
        }
        while (nextShot.wasPressed()) {
            BattlecamController.nextShot(client);
        }
        while (previousShot.wasPressed()) {
            BattlecamController.previousShot(client);
        }
        BattlecamController.tick(client);
    }

    private static KeyBinding register(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.better_cobblemon_battlecam." + name,
            InputUtil.Type.KEYSYM,
            key,
            "category.better_cobblemon_battlecam"
        ));
    }
}
