package jbro.cobblemon.bettermusic.integration.mcc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jbro.cobblemon.bettermusic.api.BattleMusicContentProvider;
import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders;
import jbro.cobblemon.bettermusic.api.PendingBattleMusic;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.client.Cobblemon173BattleMusicSampler;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.api.ScreenMusicProvider;
import jbro.cobblemon.bettermusic.api.ScreenMusicProviders;
import jbro.cobblemon.mcc.api.battle.MccBattleTag;
import jbro.cobblemon.mcc.api.client.MccClientContext;
import jbro.cobblemon.mcc.api.client.MccPendingBattle;
import jbro.cobblemon.mcc.api.client.MccPendingBattles;
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentClient;

/**
 * The providers that read MCC's client API. This is the only class that names MCC types, and it is loaded only after
 * {@link MoreCobblemonContentsIntegration} has seen MCC installed.
 */
final class MccMusicProviders {
    private MccMusicProviders() {
    }

    static Registration register(String providerId) {
        // An MCC without the client context API fails here, before anything is registered.
        MccClientContext.current();
        var battle = BattleMusicContentProviders.global().register(providerId, new BattleMusicContentProvider() {
            @Override
            public Optional<String> contentId(UUID battleId) {
                return Optional.ofNullable(ManagedBattleContentClient.contentId(battleId));
            }

            @Override
            public List<String> contentKeys(UUID battleId) {
                MccBattleTag tag = ManagedBattleContentClient.tag(battleId);
                return tag == null ? List.of() : MccMusicKeys.battle(tag.getContentId(), tag.getStage(), tag.getOpponentId());
            }

            @Override
            public Set<String> knownContentKeys() {
                return MccMusicKeys.KNOWN_BATTLE_KEYS;
            }
        });
        var screen = ScreenMusicProviders.global().register(providerId, new ScreenMusicProvider() {
            @Override
            public List<String> screenKeys() {
                String tab = MccClientContext.current().getHubTab();
                return tab == null ? List.of() : MccMusicKeys.hub(tab);
            }

            @Override
            public Set<String> knownScreenKeys() {
                return MccMusicKeys.KNOWN_SCREEN_KEYS;
            }
        });
        PendingBattleMusic.supply(MccMusicProviders::pendingBattle);
        return new Registration(
            battle == BattleMusicContentProviders.RegistrationStatus.REGISTERED,
            screen == BattleMusicContentProviders.RegistrationStatus.REGISTERED
        );
    }

    /** MCC's battle held behind its entry transition, described as the opened battle will be. */
    private static Optional<PendingBattleMusic.Pending> pendingBattle() {
        MccPendingBattle pending = MccPendingBattles.current();
        if (pending == null) {
            return Optional.empty();
        }
        var type = "trainer".equals(pending.getStyle()) ? BattleMusicConfig.BattleType.TRAINER : BattleMusicConfig.BattleType.WILD;
        Set<String> species = new LinkedHashSet<>();
        if (pending.getSpecies() != null) {
            species.addAll(Cobblemon173BattleMusicSampler.battleMusicSpeciesKeys(pending.getSpecies(), pending.getForm()));
        }
        Set<BattleMusicContext.Label> labels = new LinkedHashSet<>();
        if (type == BattleMusicConfig.BattleType.WILD) {
            if (pending.getLabels().contains("alpha")) labels.add(BattleMusicContext.Label.ALPHA);
            if (pending.getLabels().contains("legendary")) labels.add(BattleMusicContext.Label.LEGENDARY);
            if (pending.getLabels().contains("ultra_beast")) labels.add(BattleMusicContext.Label.ULTRA_BEAST);
        }
        var context = new BattleMusicContext(type, species, labels,
            BattleMusicContentProviders.global().resolveKeys(pending.getBattleId()));
        return Optional.of(new PendingBattleMusic.Pending(pending.getBattleId(), context));
    }

    record Registration(boolean battle, boolean screen) {
    }
}
