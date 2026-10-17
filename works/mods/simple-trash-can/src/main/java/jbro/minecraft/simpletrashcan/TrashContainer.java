package jbro.minecraft.simpletrashcan;

import net.minecraft.world.SimpleContainer;

/** The one-item store behind a player's trash slot. Kept in memory only. */
public final class TrashContainer extends SimpleContainer {
    public TrashContainer() {
        super(1);
    }
}
