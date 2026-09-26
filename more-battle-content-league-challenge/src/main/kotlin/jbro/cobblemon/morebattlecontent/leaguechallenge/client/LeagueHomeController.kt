package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueAction
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueLiveHomeState
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeOpenRequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** Opens only on explicit server requests; background snapshots must not steal another screen. */
internal object LeagueHomeController {
    val state = LeagueLiveHomeState()
    private var tick = 0L
    private var pendingUntil = 0L
    private val openRequest = LeagueHomeOpenRequest()

    fun register() {
        LeagueClientSession.observe { view ->
            val client = Minecraft.getInstance()
            state.accept(view)
            if (view == null) {
                dismiss()
                if (client.screen is LeagueHomeScreen) client.setScreen(null)
            } else {
                if (view.openScreen) openRequest.request(view.nonce, tick)
                val screen = client.screen as? LeagueHomeScreen
                if (view.runChallenge != null && !view.awaitingNext && view.errorKey == null) {
                    if (screen != null) client.setScreen(null)
                } else screen?.refresh()
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick++
            if (state.pending && tick >= pendingUntil) {
                state.accept(state.view?.copy(errorKey = "screen.cobblemon_more_battle_content_league_challenge.live.timeout"))
                (client.screen as? LeagueHomeScreen)?.refresh()
            }
            val battleActive = CobblemonClient.battle != null
            if (battleActive) {
                if (client.screen is LeagueHomeScreen) client.setScreen(null)
            }
            if (openRequest.consume(state.view?.nonce, tick, client.level != null && client.player != null,
                    battleActive, client.screen == null || client.screen is LeagueHomeScreen)) {
                client.setScreen(LeagueHomeScreen())
            }
        }
    }

    fun send(action: LeagueAction) {
        if (!state.begin(action)) return
        pendingUntil = tick + 100
        if (!LeagueClientSession.send(action, state.selectedId.orEmpty())) {
            state.accept(state.view?.copy(errorKey = "screen.cobblemon_more_battle_content_league_challenge.live.disconnected"))
        }
        (Minecraft.getInstance().screen as? LeagueHomeScreen)?.refresh()
    }

    fun dismiss() { openRequest.dismiss() }
}
