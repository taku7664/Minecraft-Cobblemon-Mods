/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.cobblemon.mod.common.client.CobblemonClient
 *  com.cobblemon.mod.common.client.battle.ClientBattle
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.SpectateEdge;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import java.util.UUID;

public final class CobblemonSpectateWatcher {
    private static UUID lastBattleId;
    private static boolean lastSpectating;

    private CobblemonSpectateWatcher() {
    }

    public static SpectateEdge poll() {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            SpectateEdge edge = lastBattleId != null ? SpectateEdge.STOP : SpectateEdge.NONE;
            lastBattleId = null;
            lastSpectating = false;
            return edge;
        }
        UUID battleId = battle.getBattleId();
        boolean spectating = battle.getSpectating();
        SpectateEdge edge = SpectateEdge.NONE;
        if (!lastSpectating && spectating) {
            edge = SpectateEdge.START;
        } else if (lastSpectating && !spectating) {
            edge = SpectateEdge.STOP;
        } else if (lastBattleId != null && !lastBattleId.equals(battleId) && spectating) {
            edge = SpectateEdge.START;
        }
        lastBattleId = battleId;
        lastSpectating = spectating;
        return edge;
    }
}
