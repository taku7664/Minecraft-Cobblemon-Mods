package jbro.cobblemon.morebattlecontent.leaguechallenge.client

internal enum class LeagueUiBackend(val id: String) {
    CODE("code"),
    OWO("owo");

    companion object {
        fun parseOrNull(value: String?): LeagueUiBackend? =
            entries.firstOrNull { backend -> backend.id.equals(value?.trim(), ignoreCase = true) }
    }
}
