package jbro.cobblemon.bettermusic.integration.mcc;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jbro.cobblemon.bettermusic.api.BattleMusicContentProvider;
import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders;
import jbro.cobblemon.bettermusic.api.ScreenMusicProvider;
import jbro.cobblemon.bettermusic.api.ScreenMusicProviders;
import jbro.cobblemon.mcc.api.battle.MccBattleTag;
import jbro.cobblemon.mcc.api.client.MccClientContext;
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
        return new Registration(
            battle == BattleMusicContentProviders.RegistrationStatus.REGISTERED,
            screen == BattleMusicContentProviders.RegistrationStatus.REGISTERED
        );
    }

    record Registration(boolean battle, boolean screen) {
    }
}
