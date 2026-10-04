package jbro.cobblemon.battlecam;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;

public final class BattlecamModePolicy {
    private BattlecamModePolicy() {
    }

    public static BattleCamState.Mode modeFor(BattlecamConfig config, BattlecamBattleType battleType) {
        if (!config.enabled(battleType)) {
            return BattleCamState.Mode.OFF;
        }
        return switch (config.defaultMode(battleType)) {
            case OFF -> BattleCamState.Mode.OFF;
            case AUTO -> BattleCamState.Mode.AUTO;
            case MANUAL -> BattleCamState.Mode.MANUAL;
        };
    }

    public static BattleCamState.Mode modeForCurrentBattle(
        BattleViewContext context,
        BattleCamState.Mode fallback
    ) {
        if (context == BattleViewContext.NONE) {
            return fallback;
        }
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            return fallback;
        }
        return modeFor(BattlecamConfigStore.current(), BattlecamBattleType.classify(battle));
    }

    public static void applyToActiveBattle() {
        BattleCamClient.STATE.applyConfiguredMode();
    }
}
