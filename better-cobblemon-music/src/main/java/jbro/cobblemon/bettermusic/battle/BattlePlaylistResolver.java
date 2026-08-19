package jbro.cobblemon.bettermusic.battle;

import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class BattlePlaylistResolver {
    private static final java.util.List<String> ROLE_PRIORITY = java.util.List.of(
        "champion", "elite", "gym", "rival"
    );
    private final BattleMusicConfig config;

    public BattlePlaylistResolver(BattleMusicConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Selection select(BattleMusicContext context) {
        Objects.requireNonNull(context, "context");
        for (int index = 0; index < config.pokemon().size(); index++) {
            var rule = config.pokemon().get(index);
            boolean allowedType = rule.only().isEmpty() || rule.only().contains(context.type());
            boolean hasSpecies = context.opponentSpecies().stream().anyMatch(rule.species()::contains);
            if (allowedType && hasSpecies) {
                return new Selection("battle.pokemon:" + index, rule.playlist());
            }
        }

        if (context.contentId().isPresent()) {
            String contentId = context.contentId().orElseThrow();
            PlaylistDefinition playlist = config.content().get(contentId);
            if (playlist != null) {
                return new Selection("battle.content:" + contentId, playlist);
            }
        }

        if (context.type() == BattleMusicConfig.BattleType.TRAINER) {
            for (String role : ROLE_PRIORITY) {
                PlaylistDefinition playlist = config.roles().get(role);
                if (playlist != null && context.trainerRoles().contains(role)) {
                    return new Selection("battle.role:" + role, playlist);
                }
            }
            return new Selection("battle.trainer", config.trainer());
        }

        if (context.type() == BattleMusicConfig.BattleType.WILD) {
            Optional<Selection> special = special(
                context,
                BattleMusicContext.Label.ULTRA_BEAST,
                "battle.ultra_beast",
                config.ultraBeast()
            ).or(() -> special(
                context,
                BattleMusicContext.Label.LEGENDARY,
                "battle.legendary",
                config.legendary()
            ));
            if (special.isPresent()) {
                return special.orElseThrow();
            }
            return new Selection("battle.wild", config.wild());
        }

        return new Selection("battle.pvp", config.pvp());
    }

    private static Optional<Selection> special(
        BattleMusicContext context,
        BattleMusicContext.Label label,
        String id,
        Optional<PlaylistDefinition> playlist
    ) {
        return context.labels().contains(label) ? playlist.map(value -> new Selection(id, value)) : Optional.empty();
    }

    public record Selection(String id, PlaylistDefinition playlist) {
        public Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
        }
    }
}
