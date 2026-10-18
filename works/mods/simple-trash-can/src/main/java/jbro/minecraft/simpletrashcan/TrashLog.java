package jbro.minecraft.simpletrashcan;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a server log line whenever a trash item is actually deleted, so an admin can tell what a player threw away.
 * Placing an item is not logged: it can still be taken back.
 */
public final class TrashLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTrashCan");

    public enum Reason {
        REPLACED("replaced"),
        DISCONNECT("disconnect"),
        DEATH("death");

        private final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    private TrashLog() {
    }

    public static void deleted(ServerPlayer player, ItemStack stack, Reason reason) {
        if (stack.isEmpty()) return;
        LOGGER.info("{} deleted {}x {} ({})", player.getGameProfile().getName(), stack.getCount(),
            BuiltInRegistries.ITEM.getKey(stack.getItem()), reason.label);
    }
}
