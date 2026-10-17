package jbro.minecraft.simpletrashcan;

import net.minecraft.resources.ResourceLocation;

/**
 * One trash slot beside the survival inventory. An item put there stays until another one replaces it, so a mistake
 * can be taken back; the slot is never saved, so leaving the game, a crash or a death without keepInventory empties it.
 */
public final class SimpleTrashCan {
    public static final String MOD_ID = "simple_trash_can";

    private SimpleTrashCan() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
