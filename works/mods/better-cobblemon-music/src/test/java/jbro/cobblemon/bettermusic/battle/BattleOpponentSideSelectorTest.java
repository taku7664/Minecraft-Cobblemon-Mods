package jbro.cobblemon.bettermusic.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BattleOpponentSideSelectorTest {
    @Test
    void spectatorUsesOnlyNpcOrWildSidesWhenTheyExist() {
        assertEquals(
            Set.of(1),
            BattleOpponentSideSelector.spectatorSides(List.of(
                Set.of(BattleOpponentSideSelector.ActorKind.PLAYER),
                Set.of(BattleOpponentSideSelector.ActorKind.NPC)
            ))
        );
        assertEquals(
            Set.of(1),
            BattleOpponentSideSelector.spectatorSides(List.of(
                Set.of(BattleOpponentSideSelector.ActorKind.PLAYER),
                Set.of(BattleOpponentSideSelector.ActorKind.WILD)
            ))
        );
    }

    @Test
    void spectatorKeepsBothSidesForPvp() {
        assertEquals(
            Set.of(0, 1),
            BattleOpponentSideSelector.spectatorSides(List.of(
                Set.of(BattleOpponentSideSelector.ActorKind.PLAYER),
                Set.of(BattleOpponentSideSelector.ActorKind.PLAYER)
            ))
        );
    }
}
