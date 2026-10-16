package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.league.network.LeagueAction
import jbro.cobblemon.mcc.league.ui.LeagueLiveHomeState
import jbro.cobblemon.mcc.league.ui.LeagueHomeOpenRequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * League lives in the MCC hub's League tab. The hub opens on it only on explicit server requests (a League
 * terminal, or a finished battle); background snapshots must not steal another screen.
 */
internal object LeagueHomeController {
    const val CONTENT: String = ManagedBattleContentIds.LEAGUE_CHALLENGE

    val state = LeagueLiveHomeState()
    private var tick = 0L
    private var pendingUntil = 0L
    private val openRequest = LeagueHomeOpenRequest()
    private var openedSession = false

    fun register() {
        LeagueClientSession.observe { view ->
            val client = Minecraft.getInstance()
            val legacyError = view?.errorKey
            state.accept(view?.copy(errorKey = null))
            if (view == null) {
                dismiss()
                if (showingLeague()) client.setScreen(null)
            } else {
                if (view.openScreen) openRequest.request(view.nonce, tick)
                if (view.runChallenge != null && !view.awaitingNext && legacyError == null) {
                    if (showingLeague()) client.setScreen(null)
                } else refresh()
                legacyError?.let(LeagueErrorPopup::show)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick++
            if (state.pending && tick >= pendingUntil) {
                fail("screen.more_cobblemon_contents_league_challenge.live.timeout")
            }
            val battleActive = CobblemonClient.battle != null
            if (battleActive && showingLeague()) client.setScreen(null)
            val screenAvailable = client.screen.let { it == null || it is MccHubScreen }
            if (openRequest.consume(state.view?.nonce, tick, client.level != null && client.player != null,
                    battleActive, screenAvailable)) {
                openedSession = true
                MccHubScreen.open(CONTENT)
            }
        }
    }

    /** True once after the server opened the hub on League, whose session the tab then reuses. */
    fun takeOpenedSession(): Boolean = openedSession.also { openedSession = false }

    fun send(action: LeagueAction) {
        if (!state.begin(action)) return
        pendingUntil = tick + 100
        val branch = state.finalChoiceId.takeIf { state.challenges.any { it.id == state.selectedId && it.finalStage } }
        if (!LeagueClientSession.send(action, state.selectedId.orEmpty(), finalChoiceId = branch)) {
            fail("screen.more_cobblemon_contents_league_challenge.live.disconnected")
        }
        refresh()
    }

    /** Shows the League of region [id]; the server answers with its view, or keeps the current one when it is locked. */
    fun selectRegion(id: String) {
        val view = state.view ?: return
        val next = view.regions.firstOrNull { it.id == id && it.unlocked && it.id != view.leagueId } ?: return
        if (!state.begin(LeagueAction.SELECT_LEAGUE)) return
        pendingUntil = tick + 100
        if (!LeagueClientSession.send(LeagueAction.SELECT_LEAGUE, leagueId = next.id))
            fail("screen.more_cobblemon_contents_league_challenge.live.disconnected")
        refresh()
    }

    fun dismiss() { openRequest.dismiss() }

    /** A server rejection completes the in-flight intent before its one popup is shown. */
    fun receiveError(key: String) = fail(key)

    private fun showingLeague(): Boolean {
        val hub = MccHubScreen.current ?: return false
        return Minecraft.getInstance().screen === hub && hub.selectedTabId == CONTENT
    }

    private fun refresh() {
        if (showingLeague()) MccHubScreen.current?.rebuild()
    }

    private fun fail(key: String) {
        pendingUntil = 0L
        state.reject()
        refresh()
        LeagueErrorPopup.show(key)
    }
}
