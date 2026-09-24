/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.cobblemon.mod.common.client.CobblemonClient
 *  com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon
 *  com.cobblemon.mod.common.client.battle.ClientBattle
 *  com.cobblemon.mod.common.client.battle.ClientBattlePokemon
 *  com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket
 *  kotlin.Pair
 *  net.minecraft.client.MinecraftClient
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.ReflectionUtil;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import kotlin.Pair;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"com.cobblemon.mod.common.client.net.battle.BattleHealthChangeHandler"}, remap=false)
public abstract class BattleHealthChangeHandlerMixin {
    @Inject(method={"handle"}, at={@At(value="HEAD")})
    private void battlecam$queueDamageScene(BattleHealthChangePacket packet, MinecraftClient client, CallbackInfo ci) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null || !BattleCamClient.STATE.isBattleContextActive()) {
            return;
        }
        try {
            String pnx;
            Object healthValue;
            block10: {
                block9: {
                    Object pnxValue = ReflectionUtil.invokeNoArg(packet, "getPnx");
                    healthValue = ReflectionUtil.invokeNoArg(packet, "getNewHealth");
                    if (!(pnxValue instanceof String)) break block9;
                    pnx = (String)pnxValue;
                    if (healthValue instanceof Number) break block10;
                }
                return;
            }
            Number newHealth = (Number)healthValue;
            Pair pair = battle.getPokemonFromPNX(pnx);
            ActiveClientBattlePokemon activePokemon = (ActiveClientBattlePokemon)pair.getSecond();
            ClientBattlePokemon battlePokemon = activePokemon.getBattlePokemon();
            if (battlePokemon == null) {
                return;
            }
            if (newHealth.floatValue() < battlePokemon.getHpValue()) {
                BattleCamClient.STATE.enqueueActionEvent(new BattleActionEvent(battlePokemon.getUuid(), BattleActionKind.DAMAGE, System.currentTimeMillis(), 1900L));
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}
