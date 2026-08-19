package jbro.cobblemon.bettermusic.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BattleMusicConfig(
    PlaylistDefinition wild,
    PlaylistDefinition trainer,
    PlaylistDefinition pvp,
    Map<String, PlaylistDefinition> content,
    Map<String, PlaylistDefinition> roles,
    Optional<PlaylistDefinition> legendary,
    Optional<PlaylistDefinition> ultraBeast,
    List<PokemonRule> pokemon
) {
    public BattleMusicConfig {
        Objects.requireNonNull(wild, "wild");
        Objects.requireNonNull(trainer, "trainer");
        Objects.requireNonNull(pvp, "pvp");
        content = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(content, "content")));
        roles = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(roles, "roles")));
        legendary = Objects.requireNonNull(legendary, "legendary");
        ultraBeast = Objects.requireNonNull(ultraBeast, "ultraBeast");
        pokemon = List.copyOf(Objects.requireNonNull(pokemon, "pokemon"));
    }

    public BattleMusicConfig(
        PlaylistDefinition wild,
        PlaylistDefinition trainer,
        PlaylistDefinition pvp,
        Map<String, PlaylistDefinition> roles,
        Optional<PlaylistDefinition> legendary,
        Optional<PlaylistDefinition> ultraBeast,
        List<PokemonRule> pokemon
    ) {
        this(wild, trainer, pvp, Map.of(), roles, legendary, ultraBeast, pokemon);
    }

    public record PokemonRule(Set<String> species, Set<BattleType> only, PlaylistDefinition playlist) {
        public PokemonRule {
            species = Set.copyOf(Objects.requireNonNull(species, "species"));
            if (species.isEmpty()) {
                throw new IllegalArgumentException("species must not be empty");
            }
            only = Set.copyOf(Objects.requireNonNull(only, "only"));
            Objects.requireNonNull(playlist, "playlist");
        }
    }

    public enum BattleType {
        WILD,
        TRAINER,
        PVP
    }
}
