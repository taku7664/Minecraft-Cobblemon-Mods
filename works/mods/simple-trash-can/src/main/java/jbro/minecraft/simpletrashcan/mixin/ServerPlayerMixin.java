package jbro.minecraft.simpletrashcan.mixin;

import jbro.minecraft.simpletrashcan.TrashHolder;
import jbro.minecraft.simpletrashcan.TrashLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    // The trash follows the player when the inventory does (keepInventory, leaving the End); on any other death it is
    // deleted, not dropped.
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void simpleTrashCan$keepTrash(ServerPlayer that, boolean keepEverything, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ItemStack old = ((TrashHolder) that).simpleTrashCan$trash().getItem(0);
        if (keepEverything || self.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY) || that.isSpectator()) {
            ((TrashHolder) self).simpleTrashCan$trash().setItem(0, old.copy());
        } else {
            TrashLog.deleted(self, old, TrashLog.Reason.DEATH);
        }
    }
}
