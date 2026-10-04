package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.calculation.PublicMoveOutcomeBranchProjector
import jbro.cobblemon.mcc.betterai.calculation.LocalChanceModel
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveSnapshotActionConstraints

/** Evolves the mechanics read by the existing repeated-use profile; it assigns no utility or new weights. */
internal object LocalRepeatedMoveMechanics {
    private data class Branch(val state: BattleStateView, val history: RecursiveActionHistory, val probability: Double)
    private val projecting = ThreadLocal.withInitial { false }

    fun profile(
        context: BattleDecisionContext, action: BattleActionCandidate, userId: UUID, targetId: UUID,
        rolls: List<Double>, accuracy: Double, cache: LocalProjectedActionCalculationCache,
        availabilityByUse: List<Double> = emptyList(),
    ): LocalKnockoutProfile? = projectSafely(context, action, userId, targetId, rolls, accuracy, cache, availabilityByUse)

    fun flinchProbabilities(context: BattleDecisionContext, action: BattleActionCandidate, userId: UUID, targetId: UUID,
        cache: LocalProjectedActionCalculationCache): List<Double>? {
        val side = context.state.pokemon.firstOrNull { it.battlePokemonId == userId }?.side ?: return null
        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(action, context, side) ?: return null
        val probabilities = arrayListOf<Double>()
        projectSafely(context, action, userId, targetId, rolls, LocalPublicAccuracy.probability(action, context, side),
            cache, emptyList(), probabilities) ?: return null
        return probabilities
    }

    private fun projectSafely(
        context: BattleDecisionContext, action: BattleActionCandidate, userId: UUID, targetId: UUID,
        rolls: List<Double>, accuracy: Double, cache: LocalProjectedActionCalculationCache,
        availabilityByUse: List<Double>, flinchByUse: MutableList<Double>? = null,
    ): LocalKnockoutProfile? {
        // A pivot can ask the existing leaf evaluator to compare switch-ins. That nested evaluation
        // uses the original profile so mechanics projection never recursively calls itself.
        if (projecting.get()) return null
        projecting.set(true)
        return try { project(context, action, userId, targetId, rolls, accuracy, cache, availabilityByUse, flinchByUse) }
            finally { projecting.set(false) }
    }

    private fun project(
        context: BattleDecisionContext, action: BattleActionCandidate, userId: UUID, targetId: UUID,
        rolls: List<Double>, accuracy: Double, cache: LocalProjectedActionCalculationCache,
        availabilityByUse: List<Double>, flinchByUse: MutableList<Double>?,
    ): LocalKnockoutProfile? {
        val initial = context.state
        val user = initial.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return null
        val target = initial.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return null
        if (!needsEvolution(initial, user, target, action) && availabilityByUse.all { it >= 1.0 }) return null
        val wait = BattleActionCandidate("profile:wait", BattleActionKind.WAIT)
        var branches = listOf(Branch(initial, RecursiveSnapshotActionConstraints.seed(initial), 1.0))
        val survival = arrayListOf(1.0)
        val damage = arrayListOf(0.0)
        repeat(LocalMatchupScoreCalculator.MAXIMUM_USES) { use ->
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) return null
            val next = arrayListOf<Branch>()
            var flinchProbability = 0.0
            for (branch in branches) {
                val actor = branch.state.pokemon.first { it.battlePokemonId == userId }
                val defender = branch.state.pokemon.first { it.battlePokemonId == targetId }
                if (defender.fainted || defender.hpFraction <= 0.0) continue
                val selected = if (actor.fainted || actor.hpFraction <= 0.0) wait else {
                    PublicFutureActionFactory.primitiveActionsForPokemon(branch.state, actor.side, userId,
                        context.publicActionCatalog, branch.history, includeMoveHypotheses = actor.side == BattleSide.OPPONENT).firstOrNull {
                        it.kind == BattleActionKind.USE_MOVE && PublicIds.canonical(it.moveId.orEmpty()) == PublicIds.canonical(action.moveId.orEmpty()) &&
                            (it.targets.isEmpty() || it.targets.any { slot -> slot.side == defender.side && slot.slot == defender.activeSlot })
                    } ?: wait
                }
                val available = (availabilityByUse.getOrNull(use) ?: 1.0).coerceIn(0.0, 1.0)
                val choices = if (selected === wait || available >= 1.0) listOf(selected to 1.0)
                    else listOf(selected to available, wait to (1.0 - available)).filter { it.second > 0.0 }
                for ((chosen, weight) in choices) {
                  val ally = if (actor.side == BattleSide.ALLY) chosen else wait
                  val foe = if (actor.side == BattleSide.OPPONENT) chosen else wait
                  val outcomes = PublicMoveOutcomeBranchProjector.withChanceModel(LocalChanceModel.ROLL_CLASSES) {
                    PublicSingleTurnProjector.project(branch.state, ally, foe, context, branch.history,
                        chanceEffectMode = ChanceEffectProjectionMode.BRANCH_STATE, calculationCache = cache,
                        shouldContinue = { System.currentTimeMillis() < context.deadlineEpochMillis })
                  }
                  if (outcomes.isEmpty()) return null
                  for (outcome in outcomes) {
                    if (outcome.controlEffects.any { it.kind == RecursiveControlEffectKind.FLINCH &&
                        it.sourcePokemonId == userId && it.targetPokemonId == targetId }) {
                        flinchProbability += branch.probability * weight * outcome.probability * outcome.orderProbability
                    }
                    val after = outcome.state.pokemon.first { it.battlePokemonId == targetId }
                    if (after.fainted || after.hpFraction <= 0.0) continue
                    next += Branch(outcome.state, RecursiveHistoryProjector.project(branch.history, branch.state, outcome,
                        ally, foe, publicActionCatalog = context.publicActionCatalog),
                        branch.probability * weight * outcome.probability * outcome.orderProbability)
                    // The original scalar profile has a thousand HP cells. Do not make random
                    // residual abilities create an unbounded joint state table in this input reader.
                    if (next.size > LocalKnockoutProfile.MAXIMUM_CELLS) return null
                  }
                }
            }
            flinchByUse?.add(flinchProbability.coerceIn(0.0, 1.0))
            // Equal mechanical states share a profile branch, preserving their entire probability mass.
            branches = next.groupBy { cache.fingerprints.of(it.state) to it.history }.values.map {
                it.first().copy(probability = it.sumOf(Branch::probability))
            }
            val alive = branches.sumOf(Branch::probability).coerceIn(0.0, 1.0)
            survival += alive
            damage += if (alive <= 0.0) 0.0 else branches.sumOf { branch ->
                val hp = branch.state.pokemon.first { it.battlePokemonId == targetId }.hpFraction
                (target.hpFraction - hp) * branch.probability
            } / alive
        }
        val canContinue = branches.any { branch ->
            branch.state.pokemon.any { it.battlePokemonId == userId && !it.fainted && it.hpFraction > 0.0 }
        }
        return LocalKnockoutProfile.fromProjected(survival, damage, target.hpFraction, rolls.average(),
            accuracy * (availabilityByUse.lastOrNull() ?: 1.0), canContinue)
    }

    private fun needsEvolution(state: BattleStateView, user: BattlePokemonStateView, target: BattlePokemonStateView,
        action: BattleActionCandidate): Boolean =
        user.statusId != null || target.statusId != null || user.knownVolatileEffectIds.isNotEmpty() || target.knownVolatileEffectIds.isNotEmpty() ||
            state.field.weather != null || state.field.terrain != null || state.field.roomEffects.isNotEmpty() ||
            state.field.sideConditions.values.flatten().any { it.remainingTurns != null } ||
            listOf(user, target).any { PublicIds.canonical(it.knownHeldItemId.orEmpty()) in EVOLVING_ITEMS ||
                PublicIds.canonical(it.knownHeldItemId.orEmpty()).endsWith("berry") ||
                PublicIds.canonical(it.knownAbilityId.orEmpty()) in EVOLVING_ABILITIES } ||
            action.moveDetails?.effects?.effects.orEmpty().any { it.kind in EVOLVING_EFFECTS }

    private val EVOLVING_ITEMS = setOf("leftovers", "blacksludge", "lifeorb", "weaknesspolicy", "whiteherb", "flameorb", "toxicorb", "focussash",
        "rockyhelmet", "airballoon")
    private val EVOLVING_ABILITIES = setOf("stamina", "weakarmor", "berserk", "angerpoint", "electromorphosis", "cottondown",
        "speedboost", "moody", "harvest", "regenerator", "poisonheal", "icebody", "raindish", "dryskin", "solarpower",
        "roughskin", "ironbarbs", "aftermath", "cursedbody", "disguise", "sturdy", "flamebody", "static", "poisonpoint",
        "effectspore", "cutecharm", "gooey", "tanglinghair", "poisontouch", "toxicchain", "watercompaction", "angershell",
        "steamengine", "thermalexchange", "rattled", "baddreams")
    private val EVOLVING_EFFECTS = setOf(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectKind.STATUS, BattleMoveEffectKind.VOLATILE_STATUS,
        BattleMoveEffectKind.RECOIL_FRACTION, BattleMoveEffectKind.DRAIN_FRACTION, BattleMoveEffectKind.CHARGE_TURN, BattleMoveEffectKind.RECHARGE_TURN)
}
