package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import jbro.cobblemon.morebattlecontent.api.ui.experimental.ExperimentalMbcUi
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiAction
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiActionId
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiComponentId
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiGroup
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiScreenDefinition
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiScreenId
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiStateKey
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiText

@OptIn(ExperimentalMbcUi::class)
object LeagueHomeContract {
    val OPEN_NEXT_CHALLENGE = MbcUiActionId("open_next_challenge")
    val CHALLENGE_AVAILABLE = MbcUiStateKey("challenge_available")

    val definition = MbcUiScreenDefinition(
        id = MbcUiScreenId("league_home"),
        root = MbcUiGroup(
            id = MbcUiComponentId("root"),
            children = listOf(
                MbcUiGroup(
                    id = MbcUiComponentId("header"),
                    children = listOf(
                        MbcUiText(MbcUiComponentId("title"), key("title")),
                        MbcUiText(MbcUiComponentId("rank"), key("rank")),
                        MbcUiText(MbcUiComponentId("level_cap"), key("level_cap"))
                    )
                ),
                MbcUiText(MbcUiComponentId("badges"), key("badges")),
                MbcUiText(MbcUiComponentId("next_challenge"), key("next_challenge")),
                MbcUiText(MbcUiComponentId("facilities"), key("facilities")),
                MbcUiAction(
                    id = MbcUiComponentId("challenge_action"),
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
        "screen.cobblemon_more_battle_content_league_challenge.home.$suffix"
}
