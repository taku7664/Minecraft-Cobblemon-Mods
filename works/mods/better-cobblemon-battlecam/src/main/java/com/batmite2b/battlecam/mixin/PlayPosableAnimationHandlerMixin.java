/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.entity.Entity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Coerce
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.ReflectionUtil;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"com.cobblemon.mod.common.client.net.animation.PlayPosableAnimationHandler"}, remap=false)
public abstract class PlayPosableAnimationHandlerMixin {
    @Inject(method={"handle"}, at={@At(value="HEAD")})
    private void battlecam$queueMoveScene(@Coerce Object packet, MinecraftClient client, CallbackInfo ci) {
        Number entityId;
        Object animationValue;
        block8: {
            block7: {
                if (!BattleCamClient.STATE.isBattleContextActive() || client.world == null) {
                    return;
                }
                Object entityIdValue = ReflectionUtil.invokeNoArg(packet, "getEntityId");
                animationValue = ReflectionUtil.invokeNoArg(packet, "getAnimation");
                if (!(entityIdValue instanceof Number)) break block7;
                entityId = (Number)entityIdValue;
                if (animationValue instanceof Set) break block8;
            }
            return;
        }
        Set<?> animations = (Set<?>)animationValue;
        Entity entity = client.world.getEntityById(entityId.intValue());
        if (entity == null || entity.isRemoved() || !entity.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity") || !ReflectionUtil.entityMatchesBattle(entity, BattleCamClient.STATE.activeBattleId)) {
            return;
        }
        if (PlayPosableAnimationHandlerMixin.containsCryAnimation(animations)) {
            BattleCamClient.STATE.notifyPokemonCry(entity.getUuid(), ReflectionUtil.entityPokemonUuid(entity));
        }
        if (PlayPosableAnimationHandlerMixin.isMinorAnimation(animations)) {
            return;
        }
        BattleCamClient.STATE.enqueueActionEvent(new BattleActionEvent(entity.getUuid(), BattleActionKind.MOVE, System.currentTimeMillis(), PlayPosableAnimationHandlerMixin.estimateDurationMs(animations)));
    }

    private static long estimateDurationMs(Set<?> animations) {
        long duration = 2200L;
        for (Object animationValue : animations) {
            String normalized = String.valueOf(animationValue).toLowerCase(Locale.ROOT);
            if (normalized.contains("charge") || normalized.contains("beam") || normalized.contains("blast") || normalized.contains("special") || normalized.contains("attack") || normalized.contains("move")) {
                duration = Math.max(duration, 3000L);
            }
            if (normalized.contains("quick") || normalized.contains("tackle") || normalized.contains("scratch") || normalized.contains("bite")) {
                duration = Math.max(duration, 1700L);
            }
            if (!normalized.contains("loop") && !normalized.contains("continuous") && !normalized.contains("channel")) continue;
            duration = Math.max(duration, 4200L);
        }
        return duration;
    }

    private static boolean containsCryAnimation(Set<?> animations) {
        for (Object animationValue : animations) {
            String normalized = String.valueOf(animationValue).toLowerCase(Locale.ROOT);
            if (!normalized.contains("cry")) continue;
            return true;
        }
        return false;
    }

    private static boolean isMinorAnimation(Set<?> animations) {
        for (Object animationValue : animations) {
            String normalized = String.valueOf(animationValue).toLowerCase(Locale.ROOT);
            if (!normalized.contains("recoil") && !normalized.contains("faint") && !normalized.contains("cry") && !normalized.contains("recall") && !normalized.contains("withdraw") && !normalized.contains("return") && !normalized.contains("switch") && !normalized.contains("send_out") && !normalized.contains("sendout") && !normalized.contains("capture") && !normalized.contains("sleep")) continue;
            return true;
        }
        return animations.isEmpty();
    }
}
