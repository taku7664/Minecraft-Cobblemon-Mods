package jbro.cobblemon.battlecam;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import java.util.HashSet;
import java.util.Set;

public enum BattlecamBattleType {
    WILD,
    PVE,
    PVP;

    public static BattlecamBattleType classify(ClientBattle battle) {
        Set<ActorType> actorTypes = new HashSet<>();
        for (var side : battle.getSides()) {
            for (var actor : side.getActors()) {
                actorTypes.add(actor.getType());
            }
        }
        return classify(actorTypes);
    }

    static BattlecamBattleType classify(Set<ActorType> actorTypes) {
        if (actorTypes.contains(ActorType.WILD)) {
            return WILD;
        }
        if (actorTypes.contains(ActorType.NPC)) {
            return PVE;
        }
        return PVP;
    }
}
