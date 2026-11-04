package jbro.cobblemon.battlespeed.client;

import com.cobblemon.mod.common.client.CobblemonClient;

import java.util.UUID;

/** The speeds the server sent for a battle; they only count for that battle. */
public final class ClientBattleSpeedState {
    private static volatile UUID battleId;
    private static volatile float speed = 1F;
    private static volatile float modelSpeed = 1F;

    private ClientBattleSpeedState() {
    }

    public static void set(UUID newBattleId, float newSpeed, float newModelSpeed) {
        battleId = newBattleId;
        speed = sane(newSpeed);
        modelSpeed = sane(newModelSpeed);
    }

    public static void clear() {
        battleId = null;
        speed = 1F;
        modelSpeed = 1F;
    }

    /** HUD speed, while the sped-up battle is the one on screen. */
    public static float currentSpeed() {
        UUID id = battleId;
        if (id == null) {
            return 1F;
        }
        var battle = CobblemonClient.INSTANCE.getBattle();
        return battle != null && id.equals(battle.getBattleId()) ? speed : 1F;
    }

    /** Model animation speed for a Pokémon entity that is fighting in the given battle. */
    public static float modelSpeedFor(UUID entityBattleId) {
        UUID id = battleId;
        return id != null && id.equals(entityBattleId) ? modelSpeed : 1F;
    }

    private static float sane(float value) {
        return Float.isFinite(value) && value > 0F ? value : 1F;
    }
}
