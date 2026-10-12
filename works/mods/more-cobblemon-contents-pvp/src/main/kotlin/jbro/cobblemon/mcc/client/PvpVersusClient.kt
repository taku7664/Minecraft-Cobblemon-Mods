package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.mojang.authlib.GameProfile
import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentClient
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.ui.extended.transition.BattleVersusIntro
import jbro.cobblemon.ui.extended.transition.VersusSide
import jbro.cobblemon.ui.extended.transition.VersusTier
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The versus intro over a PvP battle as it opens, on both players' screens. It is client-side only: the battle is not
 * held for it, so it plays over the battle's first moments (the first turn's clock runs under it) and fades into the
 * battle when it ends. Spectators see no intro.
 */
internal object PvpVersusClient {
    /** The content tag can reach the client a little after the battle opens; it is looked for this long. */
    private const val TAG_WAIT_TICKS = 40

    private var seen: UUID? = null
    private var waited = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    private fun tick(client: Minecraft) {
        val battle = CobblemonClient.battle ?: run {
            seen = null
            return
        }
        if (battle.battleId == seen) return
        val tag = ManagedBattleContentClient.tag(battle.battleId)
        if (tag == null) {
            if (++waited < TAG_WAIT_TICKS) return
            settle(battle.battleId)
            return
        }
        settle(battle.battleId)
        if (tag.contentId != ManagedBattleContentIds.PVP || battle.spectating) return
        val player = client.player ?: return
        val mine = if (battle.side1.actors.any { it.uuid == player.uuid }) battle.side1 else battle.side2
        val theirs = if (mine === battle.side1) battle.side2 else battle.side1
        val rival = theirs.actors.firstOrNull() ?: return
        val framing = UiModelFraming.FULL_BODY
        val rivalProfile = client.connection?.getPlayerInfo(rival.uuid)?.profile ?: GameProfile(rival.uuid, rival.displayName.string)
        val title = Component.translatable("cobblemon_ui.versus.pvp")
        BattleVersusIntro.play(VersusTier.PVP,
            VersusSide(Component.literal(player.gameProfile.name), title,
                CobblemonUiRenderContent.PlayerProfile(player.gameProfile, framing)),
            VersusSide(Component.literal(rivalProfile.name), title, CobblemonUiRenderContent.PlayerProfile(rivalProfile, framing)),
            null, false,
            // The battle is already open behind it: the white it ends in fades at once.
        ) { BattleVersusIntro.reveal() }
    }

    private fun settle(battleId: UUID) {
        seen = battleId
        waited = 0
    }
}
