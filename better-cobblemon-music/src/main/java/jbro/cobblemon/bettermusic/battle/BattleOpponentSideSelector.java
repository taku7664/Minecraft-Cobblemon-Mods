package jbro.cobblemon.bettermusic.battle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BattleOpponentSideSelector {
    private BattleOpponentSideSelector() {
    }

    public static Set<Integer> spectatorSides(List<Set<ActorKind>> sides) {
        Objects.requireNonNull(sides, "sides");
        Set<Integer> nonPlayerSides = new LinkedHashSet<>();
        for (int index = 0; index < sides.size(); index++) {
            Set<ActorKind> actors = Objects.requireNonNull(sides.get(index), "side actors");
            if (actors.contains(ActorKind.WILD) || actors.contains(ActorKind.NPC)) {
                nonPlayerSides.add(index);
            }
        }
        if (!nonPlayerSides.isEmpty()) {
            return Set.copyOf(nonPlayerSides);
        }

        Set<Integer> allSides = new LinkedHashSet<>();
        for (int index = 0; index < sides.size(); index++) {
            allSides.add(index);
        }
        return Set.copyOf(allSides);
    }

    public enum ActorKind {
        PLAYER,
        WILD,
        NPC
    }
}
