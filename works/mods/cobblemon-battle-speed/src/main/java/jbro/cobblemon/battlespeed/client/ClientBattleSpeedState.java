package jbro.cobblemon.battlespeed.client;

import com.cobblemon.mod.common.client.CobblemonClient;

import java.util.UUID;

/** The HUD speed the server sent for a battle; it only counts while that battle is the one on screen. */
public final class ClientBattleSpeedState {
    private static volatile UUID battleId;
    private static volatile float speed = 1F;

    private ClientBattleSpeedState() {
    }

    public static void set(UUID newBattleId, float newSpeed) {
        battleId = newBattleId;
        speed = Float.isFinite(newSpeed) && newSpeed > 0F ? newSpeed : 1F;
    }

    public static void clear() {
        battleId = null;
        speed = 1F;
    }

    public static float currentSpeed() {
        UUID id = battleId;
        if (id == null) {
            return 1F;
        }
        var battle = CobblemonClient.INSTANCE.getBattle();
        return battle != null && id.equals(battle.getBattleId()) ? speed : 1F;
    }
}
