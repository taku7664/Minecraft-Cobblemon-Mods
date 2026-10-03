package jbro.cobblemon.bettermusic.battle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;

/**
 * What the client knows about a battle. {@code contentKeys} names the content running it, most specific first, so
 * {@code example:league/champion} can have its own music and fall back to {@code example:league}.
 */
public record BattleMusicContext(
    BattleMusicConfig.BattleType type,
    Set<String> opponentSpecies,
    Set<Label> labels,
    List<String> contentKeys
) {
    public BattleMusicContext {
        Objects.requireNonNull(type, "type");
        opponentSpecies = Set.copyOf(Objects.requireNonNull(opponentSpecies, "opponentSpecies"));
        labels = Set.copyOf(Objects.requireNonNull(labels, "labels"));
        contentKeys = List.copyOf(Objects.requireNonNull(contentKeys, "contentKeys"));
    }

    public BattleMusicContext(
        BattleMusicConfig.BattleType type,
        Set<String> opponentSpecies,
        Set<Label> labels,
        Optional<String> contentId
    ) {
        this(type, opponentSpecies, labels, contentId.map(List::of).orElse(List.of()));
    }

    public BattleMusicContext(
        BattleMusicConfig.BattleType type,
        Set<String> opponentSpecies,
        Set<Label> labels
    ) {
        this(type, opponentSpecies, labels, List.of());
    }

    public enum Label {
        ALPHA,
        LEGENDARY,
        ULTRA_BEAST
    }
}
