package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.mcc.betterai.mechanics.LocalCriticalHitRules
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.internal.ai.PublicIds
import kotlin.math.pow
import kotlin.math.roundToInt

/** A narrow, event-free chance model for one attempted move. */
internal data class PublicMoveOutcomeBranch(
    val probability: Double,
    val hit: Boolean,
    val damageFraction: Double,
    val critical: Boolean = false,
    val hitCount: Int? = null,
    val rollPercentile: Double? = null,
)

internal data class PublicDamageRollSummary(
    val damageFraction: Double,
    val knockoutProbability: Double,
)

/** How a hit's damage roll and critical hit become branches. */
internal enum class LocalChanceModel {
    /** The sixteen rolls split into knockout and survival, one representative roll each; no random critical hits. */
    ROLL_CLASSES,
    /**
     * One roll, the high one at [FIXED_ROLL_PERCENTILE] of the sixteen, as a damage calculation reads a hit;
     * where the rolls, or a critical hit ([CRITICAL_HIT_CHANCE], 1.5x), straddle the target's HP, a knockout and
     * a survival branch at their real share instead. A critical hit that knocks out nothing is no branch.
     */
    HIGH_ROLL,
}

internal object PublicMoveOutcomeBranchProjector {
    private val chanceModel = ThreadLocal.withInitial { LocalChanceModel.ROLL_CLASSES }

    /** Runs [block] with [model] for the branches this thread projects. */
    fun <T> withChanceModel(model: LocalChanceModel, block: () -> T): T {
        val previous = chanceModel.get()
        chanceModel.set(model)
        try {
            return block()
        } finally {
            chanceModel.set(previous)
        }
    }

    fun project(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<PublicMoveOutcomeBranch> {
        val accuracy = LocalPublicAccuracy.probability(candidate, context, actingSide)
        val calculatedRolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(
            candidate,
            context,
            actingSide,
        )
        val rolls = calculatedRolls ?: fallbackDamageOutcomes(candidate, actingSide)
        val resolvedTargets = LocalPublicMoveTargets.resolve(candidate, context, actingSide)
        val targetHp = resolvedTargets.singleOrNull()?.hpFraction
        val actor = context.state.pokemon.firstOrNull { it.side == actingSide && it.activeSlot == candidate.actorSlot }
        val target = resolvedTargets.firstOrNull()
        val guaranteed = LocalCriticalHitRules.confirmed(candidate, actor, target, context.state)
        // Guaranteed critical hits are already calculated, so they take no extra crit branch.
        val criticalHits = if (candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
            !guaranteed && candidate.moveDetails?.effects?.effects.orEmpty().none { it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL }
        ) criticalChance(candidate, context, actingSide) else 0.0
        val criticalRolls = if (criticalHits > 0.0) {
            PublicBattleTacticalCalculator.conservativeDamageRollFractions(
                LocalCriticalHitRules.asCritical(candidate), context, actingSide,
            ) ?: rolls.map { it * CRITICAL_HIT_MULTIPLIER }
        } else emptyList()
        // An actual critical hit can also change the state without a knockout (Anger Point).
        val criticalReaction = target != null && LocalPublicAbilityState.effectiveKnownAbility(context.state, target) == "angerpoint"
        if (LocalDeclaredMultiHit.usesPerHitAccuracy(candidate)) {
            return perHitAccuracyBranches(candidate, accuracy, rolls, targetHp, criticalHits, criticalRolls, guaranteed, criticalReaction)
        }
        val hitBranches = damageBranches(rolls, targetHp, accuracy, criticalHits, criticalRolls, guaranteed, criticalReaction)
        val miss = if (accuracy < 1.0) {
            listOf(PublicMoveOutcomeBranch(1.0 - accuracy, hit = false, damageFraction = 0.0))
        } else {
            emptyList()
        }
        return (miss + hitBranches).filter { it.probability > 0.0 }
    }

    private fun perHitAccuracyBranches(
        candidate: BattleActionCandidate,
        accuracy: Double,
        damageRolls: List<Double>,
        targetHp: Double?,
        criticalHits: Double,
        criticalRolls: List<Double>,
        guaranteed: Boolean,
        criticalReaction: Boolean,
    ): List<PublicMoveOutcomeBranch> {
        val maximum = LocalDeclaredMultiHit.maximumCount(candidate)
        val branches = mutableListOf(PublicMoveOutcomeBranch(1.0 - accuracy, false, 0.0))
        for (hits in 1 until maximum) {
            val probability = accuracy.pow(hits) * (1.0 - accuracy)
            branches += damageBranches(damageRolls.map { it * hits }, targetHp, probability, criticalHits,
                criticalRolls.map { it * hits }, guaranteed, criticalReaction).map { it.copy(hitCount = hits) }
        }
        branches += damageBranches(damageRolls.map { it * maximum }, targetHp, accuracy.pow(maximum), criticalHits,
            criticalRolls.map { it * maximum }, guaranteed, criticalReaction).map { it.copy(hitCount = maximum) }
        return branches.filter { it.probability > 0.0 }
    }

    /**
     * KO and survival must reach distinct states: later actions can disappear or change target.
     * Keep one real roll within each class, retaining the existing compact model for other HP
     * thresholds. Direct-hit mechanics subsequently resolve Sash, Sturdy, Disguise and healing;
     * removal value comes from those resulting states, never an additional raw-roll KO bonus.
     */
    private fun damageBranches(
        rolls: List<Double>,
        targetHp: Double?,
        probability: Double,
        criticalHits: Double,
        criticalRolls: List<Double>,
        guaranteed: Boolean,
        criticalReaction: Boolean,
    ): List<PublicMoveOutcomeBranch> {
        if (rolls.isEmpty()) return listOf(PublicMoveOutcomeBranch(probability, true, 0.0))
        val usesCritical = criticalHits > 0.0 && criticalRolls.isNotEmpty() && (criticalHits >= 1.0 || criticalReaction ||
            chanceModel.get() == LocalChanceModel.HIGH_ROLL && targetHp != null && criticalRolls.any { it + DAMAGE_EPSILON >= targetHp })
        if (usesCritical) {
            return (damageBranches(rolls, targetHp, probability * (1.0 - criticalHits), 0.0, emptyList(), false, false) +
                damageBranches(criticalRolls, targetHp, probability * criticalHits, 0.0, emptyList(), true, false))
                .filter { it.probability > 0.0 }
        }
        if (chanceModel.get() == LocalChanceModel.HIGH_ROLL) return highRollBranches(rolls, targetHp, probability, guaranteed)
        val sorted = rolls.sorted()
        return rolls.groupBy { targetHp != null && it >= targetHp }.values.map { group ->
            val orderedGroup = group.sorted()
            val rank = sorted.indexOf(orderedGroup.first()) + (orderedGroup.size - 1) / 2
            PublicMoveOutcomeBranch(
                probability * group.size / rolls.size,
                true,
                summarizeDamageRolls(group, targetHp).damageFraction,
                guaranteed,
                rollPercentile = rank.toDouble() / (sorted.size - 1).coerceAtLeast(1),
            )
        }
    }

    /**
     * The fixed high roll alone, unless the rolls straddle the target's HP: then a knockout and a survival
     * branch at their real share, a critical hit's own knockouts folded into that share. Each branch keeps a
     * roll it can really reach, the survivors at their own high roll.
     */
    private fun highRollBranches(
        rolls: List<Double>,
        targetHp: Double?,
        probability: Double,
        critical: Boolean,
    ): List<PublicMoveOutcomeBranch> {
        val sorted = rolls.sorted()
        val fixed = highRoll(sorted)
        val fixedRank = ((sorted.size - 1) * FIXED_ROLL_PERCENTILE).roundToInt()
        val fixedPercentile = fixedRank.toDouble() / (sorted.size - 1).coerceAtLeast(1)
        if (targetHp == null || targetHp <= 0.0) return listOf(PublicMoveOutcomeBranch(probability, true, fixed, critical,
            rollPercentile = fixedPercentile))
        val knockouts = sorted.count { it + DAMAGE_EPSILON >= targetHp }
        val knockoutShare = knockouts.toDouble() / sorted.size
        if (knockoutShare <= 0.0 || knockoutShare >= 1.0) return listOf(PublicMoveOutcomeBranch(probability, true, fixed, critical,
            rollPercentile = fixedPercentile))
        val knockoutRoll = if (fixed + DAMAGE_EPSILON >= targetHp) fixed
            else sorted.first { it + DAMAGE_EPSILON >= targetHp }
        val survivors = sorted.filter { it + DAMAGE_EPSILON < targetHp }
        val survivalRank = ((survivors.size - 1) * FIXED_ROLL_PERCENTILE).roundToInt()
        val survivalRoll = survivors[survivalRank]
        val knockoutRank = if (fixed + DAMAGE_EPSILON >= targetHp) fixedRank else sorted.indexOfFirst { it + DAMAGE_EPSILON >= targetHp }
        return listOf(
            PublicMoveOutcomeBranch(probability * knockoutShare, true, knockoutRoll, critical,
                rollPercentile = knockoutRank.toDouble() / (sorted.size - 1).coerceAtLeast(1)),
            PublicMoveOutcomeBranch(probability * (1.0 - knockoutShare), true, survivalRoll, critical,
                rollPercentile = survivalRank.toDouble() / (sorted.size - 1).coerceAtLeast(1)),
        )
    }

    private fun highRoll(sorted: List<Double>): Double = sorted[((sorted.size - 1) * FIXED_ROLL_PERCENTILE).roundToInt()]

    /**
     * Keeps one mechanically possible representative roll for recursion. With an even roll count,
     * the lower middle value is used so the projected state never invents an averaged damage roll.
     */
    fun summarizeDamageRolls(
        rolls: List<Double>,
        targetHpFraction: Double?,
    ): PublicDamageRollSummary {
        if (rolls.isEmpty()) return PublicDamageRollSummary(0.0, 0.0)
        val sorted = rolls.sorted()
        val knockoutProbability = targetHpFraction?.takeIf { it > 0.0 }?.let { hp ->
            sorted.count { damage -> damage + DAMAGE_EPSILON >= hp }.toDouble() / sorted.size
        } ?: 0.0
        return PublicDamageRollSummary(
            damageFraction = sorted[(sorted.size - 1) / 2],
            knockoutProbability = knockoutProbability,
        )
    }

    private fun fallbackDamageOutcomes(
        candidate: BattleActionCandidate,
        actingSide: BattleSide,
    ): List<Double> {
        if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return listOf(0.0)
        val range = candidate.facts?.standardDamageFractionRange ?: return listOf(0.0)
        return listOf(if (actingSide == BattleSide.ALLY) range.minimum else range.maximum)
    }

    /**
     * The crit chance at this move's crit stage: high-crit moves (Stone Edge, Leaf Blade), Super Luck, a Scope Lens
     * or Razor Claw raise it; Battle Armor and Shell Armor, publicly known, rule it out.
     */
    private fun criticalChance(candidate: BattleActionCandidate, context: BattleDecisionContext, actingSide: BattleSide): Double {
        val state = context.state
        val target = LocalPublicMoveTargets.resolve(candidate, context, actingSide).firstOrNull()
        val actor = state.pokemon.firstOrNull { it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted }
        if (LocalCriticalHitRules.blocked(candidate, actor, target, state)) return 0.0
        var stage = 0
        if (PublicIds.canonical(candidate.moveId.orEmpty()) in HIGH_CRIT_MOVES) stage++
        if (actor != null) {
            if (LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "superluck") stage++
            if (LocalPublicItemState.activeItemId(state, actor) in CRIT_ITEMS) stage++
            if (actor.knownVolatileEffectIds.any { PublicIds.canonical(it) in FOCUS_VOLATILES }) stage += 2
            if (actor.knownVolatileEffectIds.any { PublicIds.canonical(it) == "laserfocus" }) stage += 3
        }
        return when (stage) {
            0 -> CRITICAL_HIT_CHANCE
            1 -> 1.0 / 8.0
            2 -> 1.0 / 2.0
            else -> 1.0
        }
    }

    private val CRIT_ITEMS = setOf("scopelens", "razorclaw")
    private val FOCUS_VOLATILES = setOf("focusenergy", "dragoncheer")
    private val HIGH_CRIT_MOVES = setOf(
        "stoneedge", "leafblade", "drillrun", "psychocut", "nightslash", "crabhammer", "crosschop", "slash",
        "shadowclaw", "aircutter", "attackorder", "aeroblast", "blazekick", "crosspoison", "karatechop",
        "poisontail", "razorleaf", "razorwind", "skyattack", "spacialrend", "snipeshot", "esperwing", "aquacutter",
        "triplearrows", "ivycudgel",
    )

    private const val DAMAGE_EPSILON = 1e-9

    /** Stage-zero critical hit chance since Generation 7. */
    const val CRITICAL_HIT_CHANCE = 1.0 / 24.0
    private const val CRITICAL_HIT_MULTIPLIER = 1.5
    /** Where among the sorted rolls the fixed roll sits: the high end, as damage calculations are read. */
    const val FIXED_ROLL_PERCENTILE = 0.9
}
