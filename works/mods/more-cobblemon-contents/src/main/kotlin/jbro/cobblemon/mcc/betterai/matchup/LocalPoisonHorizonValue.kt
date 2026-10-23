package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.internal.ai.*

/** A fixed residual horizon, not an estimate of how many turns the target will actually stay.
 * No attacks or team duels are simulated. Status-dependent stats and item transitions retain
 * the existing team-exchange evaluator rather than silently losing their non-HP value.
 */
internal object LocalPoisonHorizonValue {
    fun read(action: BattleActionCandidate, context: BattleDecisionContext, target: BattlePokemonStateView,
        status: String, lands: Boolean, accuracy: Double, before: Double, tuning: LocalDecisionTuning): LocalDebuffValue.Reading? {
        val turns = tuning.poisonEvaluationTurns ?: return null
        require(turns > 0)
        if (!lands) return LocalDebuffValue.Reading(before, before, accuracy, 0.0)
        if (LocalDynamax.damageShare(target) != 1.0) return null
        if (target.knownAbilityId == null) return null
        val ability = LocalPublicAbilityState.effectiveKnownAbility(context.state, target)
        // The native Poison Heal callback is onDamage, not onResidual. Share the residual
        // model's classification so a full-HP holder keeps its future healing value too.
        if (LocalEndTurnStateProjector.poisonHeals(ability, PublicIds.canonical(status))) return null
        // Guts, Quick Feet, Marvel Scale, Shed Skin, Hydration, etc. have a benefit/cure
        // beyond a deterministic HP schedule. Keep their existing complete evaluation.
        if (ability != null && runCatching {
            val data = EngineRuntimeDex.current().second.ability(ability)
            STAT_OR_CURE_HOOKS.any(data::declares) || data.declares("onResidual")
        }.getOrDefault(true)) return null
        if (runCatching { context.state.pokemon.any { holder -> holder.activeSlot != null && !holder.fainted &&
                LocalPublicAbilityState.effectiveKnownAbility(context.state, holder)?.let {
                    EngineRuntimeDex.current().second.ability(it).declares("onAnyFaint") } == true }
            }.getOrDefault(true)) return null
        val poisoned = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) it.copyState(statusId = status) else it
        })
        val updated = LocalPublicStatusBerry.afterUpdate(poisoned)
        if (updated.pokemon.any { next -> next.knownHeldItemId != poisoned.pokemon.first {
                it.battlePokemonId == next.battlePokemonId }.knownHeldItemId }) return null
        fun horizon(initial: BattleStateView): BattlePokemonStateView {
            var board = initial
            var ticks = LocalBadPoisonCounter.seed(initial).mapValues { (_, completed) -> completed + 1 }
            // This newly inflicted poison starts at tick one regardless of older public events.
            if (initial === updated) ticks = ticks + (target.battlePokemonId to 1)
            repeat(turns) {
                board = LocalEndTurnStateProjector.project(board, badPoisonTurnsByPokemon = ticks)
                ticks = ticks.mapValues { (_, n) -> (n + 1).coerceAtMost(LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN) }
            }
            return board.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        }
        val plain = horizon(context.state)
        val after = horizon(updated)
        // An end-of-turn berry transition also has item/Unburden value outside this HP-only model.
        if (plain.knownHeldItemId != target.knownHeldItemId || after.knownHeldItemId != target.knownHeldItemId) return null
        val hpLoss = plain.hpFraction - after.hpFraction
        val ko = (if (after.fainted) 1.0 else 0.0) - (if (plain.fainted) 1.0 else 0.0)
        val koPrice = LocalPokemonWorth.knockoutScore(target.battlePokemonId, tuning)
        val score = (hpLoss * tuning.boardToScore + ko * koPrice) * accuracy
        LocalDecisionInternals.record("poisonHorizon:${action.actionId}", mapOf("turns" to turns.toDouble(),
            "plainHp" to plain.hpFraction, "poisonedHp" to after.hpFraction, "hpLoss" to hpLoss,
            "knockoutDelta" to ko, "knockoutPrice" to koPrice, "accuracy" to accuracy,
            "boardToScore" to tuning.boardToScore, "value" to score))
        return LocalDebuffValue.Reading(before, before, accuracy, score)
    }

    private val STAT_OR_CURE_HOOKS = setOf("onModifyAtk", "onModifySpA", "onModifyDef", "onModifySpD", "onModifySpe",
        "onAfterSetStatus", "onSetStatus", "onCureStatus", "onHydration", "onShedSkin")
}
