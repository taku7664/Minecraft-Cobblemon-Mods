package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.state.LocalFieldEffectProjector
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID
import kotlin.math.ln

/**
 * Possible own opening plans against the lead's public type-pressure probes. This is a potential
 * heuristic, not an opponent move forecast or a probability that an unrevealed foe permits setup.
 * The unchanged ordinary opening is always available, and each plan spends its own first attack.
 */
internal object LocalDoubleLeadOpeningPotential {
    fun best(context: BattleDecisionContext, baseline: Double, score: (BattleDecisionContext) -> Double): Double {
        val own = active(context, BattleSide.ALLY).filter { "commanding" !in it.canonicalKnownVolatileEffectIds }
        val foes = active(context, BattleSide.OPPONENT)
        if (own.size != 2 || foes.isEmpty() || (own + foes).any { it.combatStats == null || it.level == null }) return baseline
        val actions = PublicFutureActionFactory.slotActions(context.state, BattleSide.ALLY, context.publicActionCatalog)
            .filter { it.kind == BattleActionKind.USE_MOVE && it.actorSlot in own.map { pokemon -> pokemon.activeSlot } }
        val setup = actions.filter { action ->
            val effects = action.moveDetails?.effects?.effects.orEmpty()
            action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS && effects.isNotEmpty() &&
                effects.all { it.probability == null || it.probability == 1.0 } &&
                (effects.all { it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER } ||
                    effects.size == 1 && effects.single().kind == BattleMoveEffectKind.FIELD_CONDITION &&
                        effects.single().target == BattleMoveEffectTarget.FIELD &&
                        effects.single().valueId?.let(PublicIds::canonical) == "trickroom")
        }
        if (setup.isEmpty()) return baseline
        val damage = HashMap<BattleActionCandidate, Double>()
        fun pressure(action: BattleActionCandidate): Double = damage.getOrPut(action) {
            foes.map { foe ->
                val hit = hitOn(action, foe, context)
                PublicBattleTacticalCalculator.conservativeDamageRollFractions(hit, context, BattleSide.ALLY)
                    ?.average()?.times(LocalPublicAccuracy.probability(hit, context, BattleSide.ALLY)) ?: 0.0
            }.average()
        }
        val bestAttack = own.associate { pokemon -> pokemon.battlePokemonId to actions
            .filter { it.actorSlot == pokemon.activeSlot && it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS }
            .maxOfOrNull(::pressure).let { it ?: 0.0 } }
        var best = baseline
        for (action in setup) {
            val actor = own.single { it.activeSlot == action.actorSlot }
            val effects = requireNotNull(action.moveDetails).effects!!.effects
            val projected = effects.fold(context.state) { state, effect ->
                if (effect.kind == BattleMoveEffectKind.STAT_STAGE)
                    LocalStatStageChange.apply(state, actor.battlePokemonId, actor.battlePokemonId, effect.statStages)
                else LocalFieldEffectProjector.apply(state, BattleSide.ALLY, effect, actor.battlePokemonId)
            }
            val after = context.copy(state = projected)
            val gain = score(after) - baseline
            // No relevant public improvement means no setup plan, even if a partner can flinch.
            if (gain <= 0.0) continue
            val room = effects.singleOrNull()?.kind == BattleMoveEffectKind.FIELD_CONDITION
            val window = if (room) projected.field.roomEffects.single { PublicIds.canonical(it.effectId) == "trickroom" }
                .remainingTurns ?: continue else context.state.remainingPokemonBySide.getValue(BattleSide.OPPONENT).coerceAtLeast(2)
            val threats = worstPressure(context, after, actor, action, foes) ?: continue
            val reference = worstPressure(context, context, actor, action, foes) ?: continue
            val support = actions.filter { candidate -> candidate.actorSlot != action.actorSlot &&
                candidate.moveDetails?.effects?.effects.orEmpty().any {
                    it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY
                } && candidate.moveDetails?.effects?.effects.orEmpty().any {
                    it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                        it.valueId?.let(PublicIds::canonical) == "flinch"
                }
            }
            fun consider(blocked: UUID?, supportAction: BattleActionCandidate?, chance: Double) {
                val incoming = threats.filterKeys { it != blocked }.values.sum()
                // These are worst category/stat/roll probes. A plan that falls to them gets no credit.
                if (incoming >= actor.hpFraction) return
                val retained = (1.0 - incoming / actor.hpFraction).coerceAtLeast(HP_FLOOR)
                val original = (1.0 - reference.values.sum() / actor.hpFraction).coerceAtLeast(HP_FLOOR)
                val supportCost = supportAction?.let { chosen ->
                    val partner = own.single { it.activeSlot == chosen.actorSlot }
                    val ordinary = bestAttack.getValue(partner.battlePokemonId)
                    if (ordinary <= 0.0) 0.0 else (1.0 - pressure(chosen) / ordinary).coerceIn(0.0, 1.0)
                } ?: 0.0
                // One lost setup attack, plus the partner's foregone damage, over the potential window.
                val capacity = 1.0 - (1.0 + supportCost) / (own.size * window)
                val delta = gain * (window - 1.0) / window + log2(capacity) + log2(retained / original) / own.size
                val accuracy = LocalPublicAccuracy.probability(action, context, BattleSide.ALLY)
                best = maxOf(best, baseline + delta * chance * accuracy)
            }
            consider(null, null, 1.0)
            for (candidate in support) {
                val target = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).singleOrNull()
                    ?.takeIf { it.side == BattleSide.OPPONENT } ?: continue
                if (LocalPublicMechanicsKernel.projectMove(candidate, context).publiclyNullified) continue
                val partner = own.single { it.activeSlot == candidate.actorSlot }
                if (LocalPublicAbilityState.effectiveKnownAbility(context.state, partner) == "sheerforce") continue
                val flinch = candidate.moveDetails!!.effects!!.effects.singleOrNull {
                    it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.valueId?.let(PublicIds::canonical) == "flinch"
                } ?: continue
                val before = probes(target, actor).mapNotNull { probe ->
                    LocalPublicTurnOrder.actsFirstProbability(context.state, BattleSide.ALLY, candidate, BattleSide.OPPONENT, probe)
                }.minOrNull() ?: continue
                val chance = before * LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY) * (flinch.probability ?: 1.0)
                if (chance > 0.0) consider(target.battlePokemonId, candidate, chance)
            }
        }
        return best
    }

    /** Per-foe maximum damage roll of the existing public type/category pressure, not a revealed move. */
    private fun worstPressure(before: BattleDecisionContext, after: BattleDecisionContext, actor: BattlePokemonStateView,
        setup: BattleActionCandidate, foes: List<BattlePokemonStateView>): Map<UUID, Double>? {
        val result = linkedMapOf<UUID, Double>()
        for (foe in foes) {
            val damages = probes(foe, actor).map { probe ->
                val early = LocalPublicTurnOrder.actsFirstProbability(before.state, BattleSide.ALLY, setup, BattleSide.OPPONENT, probe)
                    ?: return null
                fun damage(context: BattleDecisionContext) = PublicBattleTacticalCalculator
                    .conservativeDamageRollFractions(probe, context, BattleSide.OPPONENT)?.maxOrNull()
                val prior = damage(before) ?: return null
                val changed = damage(after) ?: return null
                prior * (1.0 - early) + changed * early
            }
            result[foe.battlePokemonId] = damages.maxOrNull() ?: return null
        }
        return result
    }

    private fun probes(foe: BattlePokemonStateView, target: BattlePokemonStateView) = foe.knownTypeIds.flatMap { type ->
        listOf(BattleMoveDamageCategory.PHYSICAL, BattleMoveDamageCategory.SPECIAL).map { category ->
            BattleActionCandidate("lead:public-type-pressure", BattleActionKind.USE_MOVE,
                actorSlot = requireNotNull(foe.activeSlot), moveSlot = 0,
                targets = listOf(BattleTargetSlot(BattleSide.ALLY, requireNotNull(target.activeSlot))),
                moveDetails = BattleMoveCandidateView(type, category, 80.0, 100.0, 0, 1))
        }
    }

    private fun hitOn(action: BattleActionCandidate, target: BattlePokemonStateView, context: BattleDecisionContext): BattleActionCandidate {
        val spread = LocalPublicMoveTargets.spreadMultiplier(action, context, BattleSide.ALLY) < 1.0
        return BattleActionCandidate(action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId,
            targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))), moveDetails = action.moveDetails,
            tags = action.tags + if (spread) setOf(LocalPublicMoveTargets.SPREAD_HIT_TAG) else emptySet())
    }

    private fun active(context: BattleDecisionContext, side: BattleSide) = context.state.pokemon.filter {
        it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
    }

    private fun log2(value: Double) = ln(value) / ln(2.0)
    private const val HP_FLOOR = 0.125
}
