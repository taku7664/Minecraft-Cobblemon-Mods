package jbro.cobblemon.morebattlecontent.leaguechallenge.client

internal enum class LeagueUiBackend(val id: String) {
    CODE("code"),
    OWO("owo");

    companion object {
        fun parseOrNull(value: String?): LeagueUiBackend? =
            entries.firstOrNull { backend -> backend.id.equals(value?.trim(), ignoreCase = true) }
    }
}

internal enum class LeagueUiLocale(val id: String) {
    EN_US("en_us"),
    KO_KR("ko_kr");

    companion object {
        fun parseOrNull(value: String?): LeagueUiLocale? =
            entries.firstOrNull { locale -> locale.id.equals(value?.trim(), ignoreCase = true) }
    }
}

internal interface LeagueUiVerificationProbe {
    val actionDispatched: Boolean
}
