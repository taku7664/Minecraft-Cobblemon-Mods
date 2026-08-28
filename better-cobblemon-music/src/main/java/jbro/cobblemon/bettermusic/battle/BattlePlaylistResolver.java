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
                return withBaseFallback(
                    context,
                    new Selection("battle.pokemon:" + index, rule.playlist())
                );
            }
        }

        if (context.contentId().isPresent()) {
            String contentId = context.contentId().orElseThrow();
            PlaylistDefinition playlist = config.content().get(contentId);
            if (playlist != null) {
                return withBaseFallback(
                    context,
                    new Selection("battle.content:" + contentId, playlist)
                );
            }
        }

        if (context.type() == BattleMusicConfig.BattleType.TRAINER) {
            for (String role : ROLE_PRIORITY) {
                PlaylistDefinition playlist = config.roles().get(role);
                if (playlist != null && context.trainerRoles().contains(role)) {
                    return withBaseFallback(context, new Selection("battle.role:" + role, playlist));
                }
            }
            return baseSelection(context.type());
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
                return withBaseFallback(context, special.orElseThrow());
            }
            return baseSelection(context.type());
        }

        return baseSelection(context.type());
    }

    private Selection withBaseFallback(BattleMusicContext context, Selection primary) {
        Selection base = baseSelection(context.type());
        if (primary.id().equals(base.id())) {
            return primary;
        }
        return new Selection(
            primary.id(),
            primary.playlist(),
            Optional.of(new Fallback(base.id(), base.playlist()))
        );
    }

    private Selection baseSelection(BattleMusicConfig.BattleType type) {
        return switch (type) {
            case WILD -> new Selection("battle.wild", config.wild());
            case TRAINER -> new Selection("battle.trainer", config.trainer());
            case PVP -> new Selection("battle.pvp", config.pvp());
        };
    }

    private static Optional<Selection> special(
        BattleMusicContext context,
        BattleMusicContext.Label label,
        String id,
        Optional<PlaylistDefinition> playlist
    ) {
        return context.labels().contains(label) ? playlist.map(value -> new Selection(id, value)) : Optional.empty();
    }

    public record Selection(String id, PlaylistDefinition playlist, Optional<Fallback> fallback) {
        public Selection(String id, PlaylistDefinition playlist) {
            this(id, playlist, Optional.empty());
        }

        public Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
            fallback = Objects.requireNonNull(fallback, "fallback");
        }
    }

    public record Fallback(String id, PlaylistDefinition playlist) {
        public Fallback {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
        }
    }
}
