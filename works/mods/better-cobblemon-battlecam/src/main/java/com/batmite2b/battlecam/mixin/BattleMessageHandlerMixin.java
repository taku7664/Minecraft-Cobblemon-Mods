/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Coerce
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.ReflectionUtil;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"com.cobblemon.mod.common.client.net.battle.BattleMessageHandler"}, remap=false)
public abstract class BattleMessageHandlerMixin {
    @Inject(method={"handle"}, at={@At(value="HEAD")})
    private void battlecam$queueSwitchSceneOnComeBack(@Coerce Object packet, MinecraftClient client, CallbackInfo ci) {
        Object messagesValue = ReflectionUtil.invokeNoArg(packet, "getMessages");
        if (!(messagesValue instanceof Iterable)) {
            return;
        }
        Iterable messages = (Iterable)messagesValue;
        for (Object message : messages) {
            Object textValue = ReflectionUtil.invokeNoArg(message, "getString");
            String text = textValue != null ? String.valueOf(textValue) : String.valueOf(message);
            BattleCamClient.SWITCH_WATCHER.onBattleMessage(client, BattleCamClient.STATE, text);
            BattleCamClient.GIMMICK_MESSAGE_WATCHER.onBattleMessage(client, BattleCamClient.STATE, text);
        }
    }
}
