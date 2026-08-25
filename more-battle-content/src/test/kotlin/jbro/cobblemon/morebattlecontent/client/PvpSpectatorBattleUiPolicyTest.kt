package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorBattleUiPolicyTest {
    @Test
    fun `native spectator back button is hidden only for an active MBC spectator battle`() {
        assertTrue(PvpSpectatorBattleUiPolicy.hidesNativeBackButton(loungeActive = true, battleSpectating = true))
        assertFalse(PvpSpectatorBattleUiPolicy.hidesNativeBackButton(loungeActive = false, battleSpectating = true))
        assertFalse(PvpSpectatorBattleUiPolicy.hidesNativeBackButton(loungeActive = true, battleSpectating = false))
    }

    @Test
    fun `second R release restores the detailed battle view for a bodyless spectator`() {
        assertTrue(
            PvpSpectatorBattleUiPolicy.restoresDetailedView(
                loungeActive = true,
                nativeBindingCanApplyChange = true,
                localPlayerIsSpectator = true,
                battleSpectating = true,
                battleMinimised = true,
                screenOpen = false,
                guiHidden = false,
            ),
        )
    }

    @Test
    fun `first R release and unrelated battle states remain owned by Cobblemon`() {
        val baseline = RestoreViewState()
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(nativeBindingCanApplyChange = false)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(loungeActive = false)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(localPlayerIsSpectator = false)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(battleSpectating = false)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(battleMinimised = false)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(screenOpen = true)))
        assertFalse(PvpSpectatorBattleUiPolicy.restoresDetailedView(baseline.copy(guiHidden = true)))
    }

    private data class RestoreViewState(
        val loungeActive: Boolean = true,
        val nativeBindingCanApplyChange: Boolean = true,
        val localPlayerIsSpectator: Boolean = true,
        val battleSpectating: Boolean = true,
        val battleMinimised: Boolean = true,
        val screenOpen: Boolean = false,
        val guiHidden: Boolean = false,
    )

    private fun PvpSpectatorBattleUiPolicy.restoresDetailedView(state: RestoreViewState): Boolean =
        restoresDetailedView(
            loungeActive = state.loungeActive,
            nativeBindingCanApplyChange = state.nativeBindingCanApplyChange,
            localPlayerIsSpectator = state.localPlayerIsSpectator,
            battleSpectating = state.battleSpectating,
            battleMinimised = state.battleMinimised,
            screenOpen = state.screenOpen,
            guiHidden = state.guiHidden,
        )
}
