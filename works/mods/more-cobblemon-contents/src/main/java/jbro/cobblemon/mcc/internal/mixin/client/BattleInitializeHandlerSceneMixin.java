package jbro.cobblemon.mcc.internal.mixin.client;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import jbro.cobblemon.mcc.client.BattleSceneClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A battle that opens while the last one's end is still held finishes that end first: run after it, the old end
 * would close the new battle instead.
 */
@Mixin(value = BattleInitializeHandler.class, remap = false, priority = 500)
abstract class BattleInitializeHandlerSceneMixin {
    @Inject(method = "handle", at = @At("HEAD"))
    private void mcc$finishHeldEnd(BattleInitializePacket packet, Minecraft client, CallbackInfo ci) {
        BattleSceneClient.finishHeldEnd();
    }
}
