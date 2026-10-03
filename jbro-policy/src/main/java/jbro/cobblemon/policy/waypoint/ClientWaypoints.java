package jbro.cobblemon.policy.waypoint;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-local Xaero waypoint command and quick marker, sharing one creation path. */
public final class ClientWaypoints implements ClientModInitializer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("jbro_policy");
    private static boolean teleportErrorLogged;
    private static final KeyMapping QUICK_WAYPOINT = new KeyMapping(
        "key.jbro_policy.quick_waypoint", InputConstants.Type.KEYSYM,
        InputConstants.KEY_B, "key.categories.jbro_policy");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) return;
        KeyBindingHelper.registerKeyBinding(QUICK_WAYPOINT);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                try {
                    XaeroWaypointBridge.disableTeleportForCurrentWorld();
                    teleportErrorLogged = false;
                } catch (Exception exception) {
                    if (!teleportErrorLogged)
                        LOGGER.error("Could not disable Xaero waypoint teleport", exception);
                    teleportErrorLogged = true;
                }
            }
            while (QUICK_WAYPOINT.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    try {
                        String name = XaeroWaypointBridge.create(client, null);
                        client.player.displayClientMessage(Component.translatable("message.jbro_policy.waypoint_created", name), false);
                    } catch (Exception exception) {
                        client.player.displayClientMessage(Component.translatable("message.jbro_policy.waypoint_failed"), false);
                        LOGGER.error("Could not create Xaero waypoint", exception);
                    }
                }
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("waypoint")
                .executes(context -> create(context.getSource().getClient(), null))
                .then(argument("name", StringArgumentType.greedyString())
                    .executes(context -> create(context.getSource().getClient(), StringArgumentType.getString(context, "name"))))));
    }

    private static int create(Minecraft client, String requestedName) {
        if (client.player == null) return 0;
        try {
            String name = XaeroWaypointBridge.create(client, requestedName);
            client.player.displayClientMessage(Component.translatable("message.jbro_policy.waypoint_created", name), false);
            return 1;
        } catch (Exception exception) {
            client.player.displayClientMessage(Component.translatable("message.jbro_policy.waypoint_failed"), false);
            LOGGER.error("Could not create Xaero waypoint", exception);
            return 0;
        }
    }
}
