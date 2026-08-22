package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Mechanical, event-free projection shared by both decision owners. No utility or ranking lives here. */
internal data class PublicActionOutcomeProjection(
    val coverage: BattleCalculationCoverage,
    val publiclyNullified: Boolean,
    val targetHpBefore: Double?,
    val damageOnHitFractionRange: BattleFractionRange?,
    val expectedDamageFraction: Double?,
    val targetHpAfterHitRange: BattleFractionRange?,
    val actorHpBefore: Double?,
    val expectedSelfHealingFraction: Double?,
    val expectedSelfRecoilFraction: Double?,
    val actorExpectedHpAfterSelfEffects: Double?,
    val switchEntryHpAfter: Double?,
    val opponentActionOrderResolved: Boolean,
    val unknowns: Set<BattleCalculationUnknown>,
    val components: List<PublicActionOutcomeProjection> = emptyList(),
)

internal object PublicActionOutcomeProjector {
    fun project(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): PublicActionOutcomeProjection =
        when (candidate.kind) {
            BattleActionKind.USE_MOVE -> move(candidate, context, actingSide)
            BattleActionKind.SWITCH -> switch(candidate, context)
            BattleActionKind.COMPOSITE -> composite(candidate, context, actingSide)
            BattleActionKind.WAIT, BattleActionKind.FORFEIT -> empty(candidate)
        }

    private fun move(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): PublicActionOutcomeProjection {
        val facts = candidate.facts
        val actor = active(context, actingSide, candidate.actorSlot)
        val target = opponentTarget(candidate, context, actingSide)
        val mechanics = LocalPublicMechanicsKernel.projectMove(candidate, context, actingSide)
        val accuracy = facts?.baseAccuracyProbability
            ?: candidate.moveDetails?.accuracy?.div(100.0)?.coerceIn(0.0, 1.0)
        val targetHp = target?.hpFraction
        val adjustedDamage = facts?.standardDamageFractionRange?.let { damage ->
            BattleFractionRange(
                damage.minimum.times(mechanics.knownDamageMultiplier)
                    .coerceAtMost(targetHp ?: 1.0).coerceIn(0.0, 1.0),
                damage.maximum.times(mechanics.knownDamageMultiplier)
                    .coerceAtMost(targetHp ?: 1.0).coerceIn(0.0, 1.0),
            )
        }
        val expectedDamage = adjustedDamage?.let { (it.minimum + it.maximum) / 2.0 * (accuracy ?: 0.0) }
        val afterHit = if (targetHp != null && adjustedDamage != null) {
            BattleFractionRange(
                (targetHp - adjustedDamage.maximum).coerceAtLeast(0.0),
                (targetHp - adjustedDamage.minimum).coerceAtLeast(0.0),
            )
        } else {
            null
        }
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val fixedHealing = effects.filter {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { effect ->
            val range = requireNotNull(effect.fractionRange)
            (range.minimum + range.maximum) / 2.0 * (effect.probability ?: 1.0)
        }
        val drainHealing = effects.filter {
            it.kind == BattleMoveEffectKind.DRAIN_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { effect ->
            val range = requireNotNull(effect.fractionRange)
            (expectedDamage ?: 0.0) * (range.minimum + range.maximum) / 2.0 * (effect.probability ?: 1.0)
        }
        val damageRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.RECOIL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { effect ->
            val range = requireNotNull(effect.fractionRange)
            (expectedDamage ?: 0.0) * (range.minimum + range.maximum) / 2.0 * (effect.probability ?: 1.0)
        }
        val maxHpRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.MAX_HP_RECOIL || it.kind == BattleMoveEffectKind.STRUGGLE_RECOIL
        }.sumOf { effect ->
            val range = effect.fractionRange ?: return@sumOf 0.0
            (range.minimum + range.maximum) / 2.0 * (effect.probability ?: 1.0) * (accuracy ?: 1.0)
        }
        val expectedHealing = actor?.let {
            (fixedHealing + drainHealing).coerceAtMost((1.0 - it.hpFraction).coerceAtLeast(0.0))
        }
        val expectedRecoil = actor?.let { (damageRecoil + maxHpRecoil).coerceAtMost(it.hpFraction) }
        return PublicActionOutcomeProjection(
            coverage = facts?.calculationCoverage ?: BattleCalculationCoverage.UNKNOWN,
            publiclyNullified = mechanics.publiclyNullified,
            targetHpBefore = targetHp,
            damageOnHitFractionRange = adjustedDamage,
            expectedDamageFraction = expectedDamage,
            targetHpAfterHitRange = afterHit,
            actorHpBefore = actor?.hpFraction,
            expectedSelfHealingFraction = expectedHealing,
            expectedSelfRecoilFraction = expectedRecoil,
            actorExpectedHpAfterSelfEffects = actor?.hpFraction?.let { hp ->
                (hp + (expectedHealing ?: 0.0) - (expectedRecoil ?: 0.0)).coerceIn(0.0, 1.0)
            },
            switchEntryHpAfter = null,
            opponentActionOrderResolved = facts?.actsFirstProbability == 1.0,
            unknowns = facts?.unknowns.orEmpty(),
        )
    }

    private fun switch(candidate: BattleActionCandidate, context: BattleDecisionContext): PublicActionOutcomeProjection {
        val target = LocalPublicPositionFacts.switchTarget(candidate, context)
        return blank(candidate).copy(
            actorHpBefore = LocalPublicPositionFacts.activeAlly(candidate, context)?.hpFraction,
            switchEntryHpAfter = target?.let {
                LocalTacticalSituationalEvaluator.postEntryHp(candidate, it.hpFraction)
            },
        )
    }

    private fun composite(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): PublicActionOutcomeProjection {
        val components = candidate.componentActions.map { project(it, context, actingSide) }
        return blank(candidate).copy(
            coverage = if (components.all { it.coverage == BattleCalculationCoverage.EXACT }) {
                BattleCalculationCoverage.EXACT
            } else {
                BattleCalculationCoverage.PARTIAL
            },
            publiclyNullified = components.isNotEmpty() && components.all { it.publiclyNullified },
            expectedDamageFraction = components.mapNotNull { it.expectedDamageFraction }.sum(),
            expectedSelfHealingFraction = components.mapNotNull { it.expectedSelfHealingFraction }.sum(),
            expectedSelfRecoilFraction = components.mapNotNull { it.expectedSelfRecoilFraction }.sum(),
            opponentActionOrderResolved = components.all { it.opponentActionOrderResolved },
            unknowns = components.flatMapTo(linkedSetOf()) { it.unknowns },
            components = components,
        )
    }

    private fun empty(candidate: BattleActionCandidate) = blank(candidate).copy(
        publiclyNullified = candidate.kind == BattleActionKind.WAIT,
    )

    private fun blank(candidate: BattleActionCandidate) = PublicActionOutcomeProjection(
        coverage = candidate.facts?.calculationCoverage ?: BattleCalculationCoverage.UNKNOWN,
        publiclyNullified = false,
        targetHpBefore = null,
        damageOnHitFractionRange = null,
        expectedDamageFraction = null,
        targetHpAfterHitRange = null,
        actorHpBefore = null,
        expectedSelfHealingFraction = null,
        expectedSelfRecoilFraction = null,
        actorExpectedHpAfterSelfEffects = null,
        switchEntryHpAfter = null,
        opponentActionOrderResolved = false,
        unknowns = candidate.facts?.unknowns.orEmpty(),
    )

    private fun active(context: BattleDecisionContext, side: BattleSide, slot: Int?): BattlePokemonStateView? =
        context.state.pokemon.firstOrNull { it.side == side && it.activeSlot == slot && !it.fainted }

    private fun opponentTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): BattlePokemonStateView? {
        val slots = candidate.targets.filter { it.side == BattleSide.OPPONENT }.map { it.slot }.distinct()
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        if (slots.size == 1) return active(context, targetSide, slots.single())
        return context.state.pokemon.singleOrNull {
            it.side == targetSide && it.activeSlot != null && !it.fainted
        }
    }
}
