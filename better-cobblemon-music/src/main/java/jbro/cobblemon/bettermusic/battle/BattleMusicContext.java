package jbro.cobblemon.bettermusic.battle;

import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;

public record BattleMusicContext(
    BattleMusicConfig.BattleType type,
    Set<String> opponentSpecies,
    Set<String> trainerRoles,
    Set<Label> labels,
    Optional<String> contentId
) {
    public BattleMusicContext {
        Objects.requireNonNull(type, "type");
        opponentSpecies = Set.copyOf(Objects.requireNonNull(opponentSpecies, "opponentSpecies"));
        trainerRoles = Set.copyOf(Objects.requireNonNull(trainerRoles, "trainerRoles"));
        labels = Set.copyOf(Objects.requireNonNull(labels, "labels"));
        contentId = Objects.requireNonNull(contentId, "contentId");
    }

    public BattleMusicContext(
        BattleMusicConfig.BattleType type,
        Set<String> opponentSpecies,
        Set<String> trainerRoles,
        Set<Label> labels
    ) {
        this(type, opponentSpecies, trainerRoles, labels, Optional.empty());
    }

    public enum Label {
        LEGENDARY,
        ULTRA_BEAST
    }
}
