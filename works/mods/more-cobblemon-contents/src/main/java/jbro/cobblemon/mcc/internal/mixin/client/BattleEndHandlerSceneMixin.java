package jbro.cobblemon.mcc.internal.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleEndHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import jbro.cobblemon.mcc.client.BattleSceneClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds a battle's end while its closing scene plays: the server ends the battle before it announces the winner,
 * so the scene arrives just after the end. Runs before other mods' end hooks, which then run once, on the replay.
 */
@Mixin(value = BattleEndHandler.class, remap = false, priority = 500)
abstract class BattleEndHandlerSceneMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void mcc$holdForBattleScene(BattleEndPacket packet, Minecraft client, CallbackInfo ci) {
        if (BattleSceneClient.holdBattleEnd(() -> BattleEndHandler.INSTANCE.handle(packet, client))) {
            ci.cancel();
        }
    }
}
