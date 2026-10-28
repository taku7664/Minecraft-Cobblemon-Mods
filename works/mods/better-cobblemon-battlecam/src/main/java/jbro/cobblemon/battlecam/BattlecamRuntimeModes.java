package jbro.cobblemon.battlecam;

import com.batmite2b.battlecam.client.BattleCamState;
import java.util.EnumMap;
import java.util.Map;

/**
 * Modes the player picked with the cycle key, per battle type, for this game session only.
 * The json defaults are never written; a pick wins over them until the config screen saves new ones.
 */
public final class BattlecamRuntimeModes {
    private final Map<BattlecamBattleType, BattleCamState.Mode> picked = new EnumMap<>(BattlecamBattleType.class);

    public BattleCamState.Mode resolve(BattlecamBattleType battleType, BattleCamState.Mode configured) {
        if (battleType == null) {
            return configured;
        }
        return picked.getOrDefault(battleType, configured);
    }

    public void remember(BattlecamBattleType battleType, BattleCamState.Mode mode) {
        if (battleType != null) {
            picked.put(battleType, mode);
        }
    }

    public void clear() {
        picked.clear();
    }
}
