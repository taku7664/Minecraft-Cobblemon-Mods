/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.cobblemon.mod.common.client.CobblemonClient
 *  com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon
 *  com.cobblemon.mod.common.client.battle.ClientBattle
 *  com.cobblemon.mod.common.client.battle.ClientBattlePokemon
 *  com.cobblemon.mod.common.net.messages.client.battle.BattleFaintPacket
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
import com.cobblemon.mod.common.net.messages.client.battle.BattleFaintPacket;
import kotlin.Pair;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"com.cobblemon.mod.common.client.net.battle.BattleFaintHandler"}, remap=false)
public abstract class BattleFaintHandlerMixin {
    @Inject(method={"handle"}, at={@At(value="HEAD")})
    private void battlecam$queueFaintScene(BattleFaintPacket packet, MinecraftClient client, CallbackInfo ci) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null || !BattleCamClient.STATE.isBattleContextActive()) {
            return;
        }
        try {
            Object pnxValue = ReflectionUtil.invokeNoArg(packet, "getPnx");
            if (!(pnxValue instanceof String)) {
                return;
            }
            String pnx = (String)pnxValue;
            Pair pair = battle.getPokemonFromPNX(pnx);
            ActiveClientBattlePokemon activePokemon = (ActiveClientBattlePokemon)pair.getSecond();
            ClientBattlePokemon battlePokemon = activePokemon.getBattlePokemon();
            if (battlePokemon == null) {
                return;
            }
            BattleCamClient.STATE.enqueueActionEvent(new BattleActionEvent(battlePokemon.getUuid(), BattleActionKind.FAINT, System.currentTimeMillis(), 2600L));
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}
