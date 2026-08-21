package jbro.cobblemon.bettermusic.mbc

import java.util.Optional
import java.util.UUID
import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentClient
import net.fabricmc.api.ClientModInitializer

object BetterCobblemonMusicMbcIntegrationClient : ClientModInitializer {
    const val PROVIDER_ID: String = "better_cobblemon_music_mbc:more_battle_content"

    override fun onInitializeClient() {
        val status = BattleMusicContentProviders.global().register(PROVIDER_ID, ::resolveContentId)
        check(status == BattleMusicContentProviders.RegistrationStatus.REGISTERED) {
            "More Battle Content music provider was registered more than once"
        }
    }

    @JvmStatic
    fun resolveContentId(battleId: UUID): Optional<String> =
        Optional.ofNullable(ManagedBattleContentClient.contentId(battleId))
}
