package jbro.cobblemon.bettermusic.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import org.junit.jupiter.api.Test;

final class BattlePlaylistResolverTest {
    @Test
    void pokemonRuleWinsAndCanBeRestrictedByBattleType() {
        var resolver = new BattlePlaylistResolver(config());

        assertEquals("battle.pokemon:0", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.WILD,
            Set.of("cobblemon:groudon"),
            Set.of(),
            Set.of(BattleMusicContext.Label.LEGENDARY)
        )).id());
        assertEquals("battle.trainer", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.TRAINER,
            Set.of("cobblemon:groudon"),
            Set.of(),
            Set.of(BattleMusicContext.Label.LEGENDARY)
        )).id());
    }

    @Test
    void trainerRoleThenWildClassificationThenBattleTypeDefinesFallbackPrecedence() {
        var resolver = new BattlePlaylistResolver(config());

        assertEquals("battle.role:champion", resolver.select(context(Set.of("gym", "champion"))).id());
        assertEquals("battle.trainer", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.TRAINER, Set.of(), Set.of(), Set.of(BattleMusicContext.Label.LEGENDARY)
        )).id());
        assertEquals("battle.ultra_beast", resolver.select(wildContext(BattleMusicContext.Label.ULTRA_BEAST)).id());
        assertEquals("battle.legendary", resolver.select(wildContext(BattleMusicContext.Label.LEGENDARY)).id());
        assertEquals("battle.pvp", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.PVP, Set.of(), Set.of(), Set.of()
        )).id());
        assertEquals("battle.trainer", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.TRAINER, Set.of(), Set.of(), Set.of()
        )).id());
        assertEquals("battle.wild", resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.WILD, Set.of(), Set.of(), Set.of()
        )).id());
    }

    @Test
    void configuredContentWinsBeforeTrainerRoleButAfterAnExplicitPokemonRule() {
        var resolver = new BattlePlaylistResolver(config());
        var contentId = Optional.of("cobblemon_more_battle_content:battle_tower");

        assertEquals("battle.content:cobblemon_more_battle_content:battle_tower", resolver.select(
            new BattleMusicContext(
                BattleMusicConfig.BattleType.TRAINER,
                Set.of(),
                Set.of("champion"),
                Set.of(),
                contentId
            )
        ).id());
        assertEquals("battle.pokemon:0", resolver.select(
            new BattleMusicContext(
                BattleMusicConfig.BattleType.WILD,
                Set.of("cobblemon:groudon"),
                Set.of(),
                Set.of(BattleMusicContext.Label.LEGENDARY),
                contentId
            )
        ).id());
    }

    @Test
    void formQualifiedRuleWinsBeforeItsBaseSpeciesRule() {
        BattleMusicConfig base = config();
        var resolver = new BattlePlaylistResolver(new BattleMusicConfig(
            base.wild(), base.trainer(), base.pvp(), base.content(), base.roles(),
            base.legendary(), base.ultraBeast(),
            List.of(
                new BattleMusicConfig.PokemonRule(
                    Set.of("cobblemon:necrozma#ultra"),
                    Set.of(BattleMusicConfig.BattleType.WILD),
                    playlist("ultra_necrozma")
                ),
                new BattleMusicConfig.PokemonRule(
                    Set.of("cobblemon:necrozma"),
                    Set.of(BattleMusicConfig.BattleType.WILD),
                    playlist("base_necrozma")
                )
            )
        ));

        var selection = resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.WILD,
            Set.of("cobblemon:necrozma#ultra", "cobblemon:necrozma"),
            Set.of(),
            Set.of(BattleMusicContext.Label.LEGENDARY)
        ));

        assertEquals("battle.pokemon:0", selection.id());
        assertEquals(List.of("ultra_necrozma.ogg"), selection.playlist().tracks());
    }

    @Test
    void specializedBattleSelectionsCarryTheirBattleTypeDefaultAsFallback() {
        var resolver = new BattlePlaylistResolver(config());

        var pokemon = resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.WILD,
            Set.of("cobblemon:groudon"),
            Set.of(),
            Set.of(BattleMusicContext.Label.LEGENDARY)
        ));
        assertEquals("battle.wild", pokemon.fallback().orElseThrow().id());

        var legendary = resolver.select(wildContext(BattleMusicContext.Label.LEGENDARY));
        assertEquals("battle.wild", legendary.fallback().orElseThrow().id());

        var champion = resolver.select(context(Set.of("champion")));
        assertEquals("battle.trainer", champion.fallback().orElseThrow().id());

        var ordinaryWild = resolver.select(new BattleMusicContext(
            BattleMusicConfig.BattleType.WILD, Set.of(), Set.of(), Set.of()
        ));
        assertEquals(Optional.empty(), ordinaryWild.fallback());
    }

    private static BattleMusicContext context(Set<String> roles) {
        return new BattleMusicContext(BattleMusicConfig.BattleType.TRAINER, Set.of(), roles, Set.of());
    }

    private static BattleMusicContext wildContext(BattleMusicContext.Label label) {
        return new BattleMusicContext(BattleMusicConfig.BattleType.WILD, Set.of(), Set.of(), Set.of(label));
    }

    private static BattleMusicConfig config() {
        return new BattleMusicConfig(
            playlist("wild"), playlist("trainer"), playlist("pvp"),
            java.util.Map.of("cobblemon_more_battle_content:battle_tower", playlist("tower")),
            java.util.Map.of(
                "champion", playlist("champion"),
                "elite", playlist("elite"),
                "gym", playlist("gym"),
                "rival", playlist("rival")
            ),
            Optional.of(playlist("legendary")), Optional.of(playlist("ultra")),
            List.of(new BattleMusicConfig.PokemonRule(
                Set.of("cobblemon:groudon"),
                Set.of(BattleMusicConfig.BattleType.WILD),
                playlist("groudon")
            ))
        );
    }

    private static PlaylistDefinition playlist(String name) {
        return new PlaylistDefinition(
            PlaylistDefinition.Selection.SHUFFLE, 1.0, 0.0, List.of(name + ".ogg")
        );
    }
}
