package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.api.ui.experimental.ExperimentalMccUi
import jbro.cobblemon.mcc.api.ui.experimental.MccUiAction
import jbro.cobblemon.mcc.api.ui.experimental.MccUiActionId
import jbro.cobblemon.mcc.api.ui.experimental.MccUiComponentId
import jbro.cobblemon.mcc.api.ui.experimental.MccUiGroup
import jbro.cobblemon.mcc.api.ui.experimental.MccUiScreenDefinition
import jbro.cobblemon.mcc.api.ui.experimental.MccUiScreenId
import jbro.cobblemon.mcc.api.ui.experimental.MccUiStateKey
import jbro.cobblemon.mcc.api.ui.experimental.MccUiText

@OptIn(ExperimentalMccUi::class)
object LeagueHomeContract {
    val OPEN_NEXT_CHALLENGE = MccUiActionId("open_next_challenge")
    val CHALLENGE_AVAILABLE = MccUiStateKey("challenge_available")

    val definition = MccUiScreenDefinition(
        id = MccUiScreenId("league_home"),
        root = MccUiGroup(
            id = MccUiComponentId("root"),
            children = listOf(
                MccUiGroup(
                    id = MccUiComponentId("header"),
                    children = listOf(
                        MccUiText(MccUiComponentId("title"), key("title")),
                        MccUiText(MccUiComponentId("rank"), key("rank")),
                        MccUiText(MccUiComponentId("level_cap"), key("level_cap"))
                    )
                ),
                MccUiText(MccUiComponentId("badges"), key("badges")),
                MccUiText(MccUiComponentId("next_challenge"), key("next_challenge")),
                MccUiText(MccUiComponentId("facilities"), key("facilities")),
                MccUiAction(
                    id = MccUiComponentId("challenge_action"),
                    labelKey = key("challenge"),
                    actionId = OPEN_NEXT_CHALLENGE,
                    enabledWhen = CHALLENGE_AVAILABLE
                )
            )
        ),
        declaredStateKeys = setOf(CHALLENGE_AVAILABLE),
        registeredActions = setOf(OPEN_NEXT_CHALLENGE)
    )

    private fun key(suffix: String): String =
        "screen.more_cobblemon_contents_league_challenge.home.$suffix"
}
