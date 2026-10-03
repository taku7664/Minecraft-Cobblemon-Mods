package jbro.cobblemon.mcc.client

internal data class TowerGuideSection(
    val titleKey: String,
    val bodyKey: String,
)

/** The Battle Tower guide the Tower tab shows in place of its cards. */
internal object TowerGuideContent {
    const val TITLE_KEY = "screen.more_cobblemon_contents.tower.guide.title"
    const val OPEN_KEY = "screen.more_cobblemon_contents.tower.guide.open"
    const val CLOSE_KEY = "screen.more_cobblemon_contents.tower.guide.close"
    const val BUTTON_TOOLTIP_KEY = "screen.more_cobblemon_contents.tower.guide.button.tooltip"

    val sections: List<TowerGuideSection> = listOf(
        section("overview"),
        section("registration"),
        section("setup"),
        section("registered_team"),
        section("progression"),
        section("controls"),
        section("troubleshooting"),
    )

    private fun section(id: String) = TowerGuideSection(
        titleKey = "screen.more_cobblemon_contents.tower.guide.$id.title",
        bodyKey = "screen.more_cobblemon_contents.tower.guide.$id.body",
    )
}
