package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.api.battle.TeamPreviewSelection
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.mcc.internal.tower.opponent.TowerPreviewScore

/** A Tower trainer's reading of the challenger's six, through the Better AI's team preview selection. */
internal object Cobblemon173TowerPreviewReader {
    fun read(tier: BattleTrainerTier, preview: BattleOpponentTeamPreviewView): TowerPreviewScore? {
        val scorer = TeamPreviewSelection.scorer(tier, preview) ?: return null
        return TowerPreviewScore(scorer.temperature) { set -> scorer.score(set.candidate()) }
    }

    private fun TowerPokemonSet.candidate() = TeamPreviewSelection.Candidate(
        speciesId = speciesId,
        formId = formId,
        level = battleLevel,
        moveIds = moves.map { it.substringAfter(':') },
    )
}
