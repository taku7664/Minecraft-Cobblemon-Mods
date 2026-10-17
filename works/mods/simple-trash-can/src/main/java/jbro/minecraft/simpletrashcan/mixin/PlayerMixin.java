package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashContainer;
import jbro.minecraft.simpletrashcan.TrashHolder;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Player.class)
abstract class PlayerMixin implements TrashHolder {
    @Unique
    private TrashContainer simpleTrashCan$trash;

    // Created on first use: the player's InventoryMenu asks for it from inside Player's constructor.
    @Override
    public TrashContainer simpleTrashCan$trash() {
        if (simpleTrashCan$trash == null) simpleTrashCan$trash = new TrashContainer();
        return simpleTrashCan$trash;
    }
}
