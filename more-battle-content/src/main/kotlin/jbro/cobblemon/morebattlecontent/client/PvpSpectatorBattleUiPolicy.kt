package jbro.cobblemon.morebattlecontent.client

internal object PvpSpectatorBattleUiPolicy {
    fun hidesNativeBackButton(
        loungeActive: Boolean,
        battleSpectating: Boolean,
    ): Boolean = loungeActive && battleSpectating

    fun restoresDetailedView(
        loungeActive: Boolean,
        nativeBindingCanApplyChange: Boolean,
        localPlayerIsSpectator: Boolean,
        battleSpectating: Boolean,
        battleMinimised: Boolean,
        screenOpen: Boolean,
        guiHidden: Boolean,
    ): Boolean =
        loungeActive &&
            nativeBindingCanApplyChange &&
            localPlayerIsSpectator &&
            battleSpectating &&
            battleMinimised &&
            !screenOpen &&
            !guiHidden
}
