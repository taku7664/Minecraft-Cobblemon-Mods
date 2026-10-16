package jbro.cobblemon.mcc.league.server

internal enum class LeagueErrorChannel { POPUP, CHAT }

internal object LeagueErrorDelivery {
    fun channel(popupSupported: Boolean): LeagueErrorChannel =
        if (popupSupported) LeagueErrorChannel.POPUP else LeagueErrorChannel.CHAT
}
