package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Each of the AI's Pokemon's role in its team, 0..1: its [SweepScore] on a fresh board, everyone at full HP
 * with no stages or status, against every opponent seen. Unlike the sweep score it does not move with the
 * battle; it is worked out once and again only when a new opponent is seen (a battle without team preview
 * starts with the leads alone).
 */
internal object LocalAceScore {
    fun calculate(context: BattleDecisionContext): Map<UUID, Double> {
        val fresh = context.state.copyState(pokemon = context.state.pokemon.map {
            it.copyState(hpFraction = 1.0, statusId = null, statStages = emptyMap(), fainted = false)
        })
        val scores = LocalMatchupScoreCalculator.calculate(context.copy(state = fresh))
        return fresh.pokemon.filter { it.side == BattleSide.ALLY }
            .associate { it.battlePokemonId to (scores.sweeps[it.battlePokemonId]?.score ?: 0.0) }
    }
}

/**
 * A once-per-battle mechanic kept for the ace: a mechanic candidate pays [RESERVE_SCALE] per point of ace
 * score the best other living ally able to use the same mechanic has over the user. The ace itself pays
 * nothing, and a large gain this turn can still outweigh the cost, so a Pokemon that is not the ace uses it
 * only when the moment is worth that much. Two Mega Stone holders are compared with each other; Tera with
 * the allies that have a Tera type; Dynamax and other mechanics with every living ally.
 */
internal object LocalGimmickReserve {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        aceScores: Map<UUID, Double>,
    ): Map<String, Double> {
        if (aceScores.isEmpty()) return emptyMap()
        val state = context.state
        val builds = context.exactOwnTeam?.builds.orEmpty().associateBy { it.battlePokemonId }
        fun eligible(pokemon: BattlePokemonStateView, kind: String): Boolean = when (kind) {
            MEGA -> builds[pokemon.battlePokemonId]?.heldItemId?.let(::isMegaStone) ?: true
            TERA -> builds[pokemon.battlePokemonId]?.let { it.teraTypeId != null } ?: true
            else -> true
        }
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var cost = 0.0
            for (part in parts) {
                val mechanic = part.mechanic ?: continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
                } ?: continue
                val kind = kind(mechanic.mechanicId)
                val best = state.pokemon.filter {
                    it.side == BattleSide.ALLY && it.battlePokemonId != user.battlePokemonId && !it.fainted &&
                        it.hpFraction > 0.0 && eligible(it, kind)
                }.maxOfOrNull { aceScores[it.battlePokemonId] ?: 0.0 } ?: continue
                cost += RESERVE_SCALE * (best - (aceScores[user.battlePokemonId] ?: 0.0)).coerceAtLeast(0.0)
            }
            if (cost > 0.0) out[candidate.actionId] = -cost
        }
        return out
    }

    /** Mega Stones end in "ite" (Charizardite X and Y in "itex" and "itey"); Eviolite is not one. */
    private fun isMegaStone(itemId: String): Boolean {
        val id = PublicIds.canonical(itemId)
        return id != EVIOLITE && (id.endsWith("ite") || id.endsWith("itex") || id.endsWith("itey"))
    }

    private fun kind(mechanicId: String): String = when (val id = PublicIds.canonical(mechanicId)) {
        "tera", "terastallize", "terastallization" -> TERA
        "mega", "megaevolution" -> MEGA
        else -> id
    }

    /** One point of ace score is worth one HP bar of this turn's gain. */
    const val RESERVE_SCALE = 100.0
    private const val TERA = "tera"
    private const val MEGA = "mega"
    private const val EVIOLITE = "eviolite"
}
