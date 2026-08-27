package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext

/**
 * The foresight positions, reachable from more than one test.
 *
 * They were built inside `LocalForesightScenarioTest` and are needed elsewhere: any change that
 * trades search work away has to be priced against the capability it might be trading, and that
 * pricing belongs next to the change rather than in the scenario file.
 */
internal object LocalForesightPositions {
    class Position(
        val name: String,
        val context: BattleDecisionContext,
        val patientAction: String,
    )

    /** Only the positions whose payoff lands inside a search horizon, so a failure means something. */
    fun reachable(): List<Position> = LocalForesightScenarioTest().reachablePositions()
}
