package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomAccess;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void simpleMyRoom$protectPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
        if (!SimpleMyRoom.protectionEnabled() || context.getLevel().isClientSide || !RoomDimensions.isRoom(context.getLevel())
            || !SimpleMyRoom.config().protection.preventUnauthorizedPlacement) return;
        Player player = context.getPlayer();
        if (player instanceof ServerPlayer serverPlayer && !RoomAccess.canModify(serverPlayer, context.getClickedPos())) {
            serverPlayer.sendSystemMessage(Component.literal(SimpleMyRoom.messages().placementDenied), true);
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }
}
