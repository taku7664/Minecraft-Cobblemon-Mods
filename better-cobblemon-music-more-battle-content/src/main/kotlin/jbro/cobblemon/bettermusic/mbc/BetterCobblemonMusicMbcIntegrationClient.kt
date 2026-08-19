package jbro.cobblemon.bettermusic.mbc

import java.util.Optional
import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentClient
import net.fabricmc.api.ClientModInitializer

object BetterCobblemonMusicMbcIntegrationClient : ClientModInitializer {
    const val PROVIDER_ID: String = "better_cobblemon_music_mbc:more_battle_content"

    override fun onInitializeClient() {
        val status = BattleMusicContentProviders.global().register(PROVIDER_ID) { battleId ->
            Optional.ofNullable(ManagedBattleContentClient.contentId(battleId))
        }
        check(status == BattleMusicContentProviders.RegistrationStatus.REGISTERED) {
            "More Battle Content music provider was registered more than once"
        }
    }
}
