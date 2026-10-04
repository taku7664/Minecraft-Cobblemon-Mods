/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.cobblemon.mod.common.client.CobblemonClient
 *  com.cobblemon.mod.common.client.battle.ClientBattle
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleViewContext;
import com.batmite2b.battlecam.client.ReflectionUtil;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;

public final class CobblemonBattleContextWatcher {
    private CobblemonBattleContextWatcher() {
    }

    public static String currentBattleId() {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        return battle == null ? "" : ReflectionUtil.normalizedValue(battle.getBattleId());
    }

    public static BattleViewContext poll() {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            return BattleViewContext.NONE;
        }
        return battle.getSpectating() ? BattleViewContext.SPECTATING : BattleViewContext.OWN_BATTLE;
    }
}
