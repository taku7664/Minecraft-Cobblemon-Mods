package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BattlecamBattleTypeTest {
    @Test
    void wildTakesPriorityWhenAnyWildActorParticipates() {
        assertEquals(
            BattlecamBattleType.WILD,
            BattlecamBattleType.classify(Set.of(ActorType.PLAYER, ActorType.WILD))
        );
    }

    @Test
    void npcBattleIsPveWhenNoWildActorParticipates() {
        assertEquals(
            BattlecamBattleType.PVE,
            BattlecamBattleType.classify(Set.of(ActorType.PLAYER, ActorType.NPC))
        );
    }

    @Test
    void playerOnlyBattleIsPvp() {
        assertEquals(
            BattlecamBattleType.PVP,
            BattlecamBattleType.classify(Set.of(ActorType.PLAYER))
        );
    }
}
