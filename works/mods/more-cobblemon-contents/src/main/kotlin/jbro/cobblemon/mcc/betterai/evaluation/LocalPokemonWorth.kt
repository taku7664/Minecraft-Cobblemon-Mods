package jbro.cobblemon.mcc.betterai.evaluation

import java.util.UUID

/**
 * Each Pokemon's worth, in board units, for the decision this thread is making: what it adds to the board's material
 * ([LocalBoardMaterial]) and what knocking it out is worth ([LocalTacticalSituationalEvaluator.knockoutAdjustment] and
 * the other knockout prices), in place of the same two units for every Pokemon. Set by the brain for the tiers that
 * read the matchup table ([jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.worths]); a reader outside such a
 * decision, or a Pokemon without a reading, keeps the flat [LocalDecisionTuning.livingPokemonValue] (user decision
 * 2026-10-18: each side's mean held at one Pokemon, so a knockout always takes the opponent a piece closer to losing).
 */
internal object LocalPokemonWorth {
    private val active = ThreadLocal<Map<UUID, Double>?>()
    private val denials = ThreadLocal<Map<UUID, jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.Denial>?>()

    private val actingChance = ThreadLocal<((UUID, jbro.cobblemon.mcc.internal.ai.BattleActionCandidate,
        jbro.cobblemon.mcc.internal.ai.BattleStateView) -> Double?)?>()

    /** Share the root's existing opponent forecast with the KO credit; null retains the standalone reading. */
    fun setActingChance(value: (UUID, jbro.cobblemon.mcc.internal.ai.BattleActionCandidate,
        jbro.cobblemon.mcc.internal.ai.BattleStateView) -> Double?) { actingChance.set(value) }

    /** The worths read for the last decision on any thread, for the measurement harness. */
    @Volatile
    var last: Map<UUID, Double> = emptyMap()
        private set

    fun of(id: UUID): Double? = active.get()?.get(id)

    /**
     * A knockout of [id] by [attack]: its worth, and the hit it no longer gets to use this turn when [attack] lands
     * before it ([jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.Reading.denials]). Who moves first is read
     * from the two moves' priorities; at a tie, from [fallbackFirst] at priority 0, by Speed otherwise.
     * Without the hit a Scald that finished a 1% Probopass read as worth less than Protect (2026-10-18).
     */
    fun knockoutScore(
        id: UUID?,
        tuning: LocalDecisionTuning,
        attack: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate,
        state: jbro.cobblemon.mcc.internal.ai.BattleStateView,
        fallbackFirst: Double?,
    ): Double {
        val worth = knockoutScore(id, tuning)
        val denial = id?.let { denials.get()?.get(it) } ?: return worth
        val mine = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.effectivePriority(
            state, jbro.cobblemon.mcc.internal.ai.BattleSide.ALLY, attack)
        val actor = state.pokemon.firstOrNull {
            it.side == jbro.cobblemon.mcc.internal.ai.BattleSide.ALLY && it.activeSlot == attack.actorSlot && !it.fainted
        }
        val first = when {
            mine > denial.priority -> 1.0
            mine < denial.priority -> 0.0
            else -> {
                val foe = state.pokemon.firstOrNull { it.battlePokemonId == id }
                val speed = if (actor != null && foe != null) {
                    jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.speedOrderProbability(state, actor, foe)
                } else null
                // [fallbackFirst] is the attack's order against a priority-0 reply, Quick Claw, Custap and the observed
                // order included: kept when both are at 0, which is what it was read for (Codex review of c62d644b).
                (if (mine == 0) fallbackFirst ?: speed else speed ?: fallbackFirst) ?: UNKNOWN_ORDER
            }
        }
        // Its whole worth, the piece the other side loses (user, 2026-10-21: no urgency scales it; what waiting would gain is
        // the waiting move's own value), and only if the attack lands before it knocks the attacker out: first, or second and
        // still standing. Read as landing anyway, a +2 Mega Garchomp at 46% Earthquaked
        // into a faster Dragapult that Dragon Darted it first, Togekiss behind immune (hand-played Cynthia, 2026-10-20).
        // Its threat to this attacker, not to the one beside it (LocalKnockoutWorth.Denial.threatOn).
        val threat = actor?.let { denial.threatOn[it.battlePokemonId] } ?: denial.threat
        val withoutPrediction = first.coerceIn(0.0, 1.0) + (1.0 - first.coerceIn(0.0, 1.0)) * (1.0 - threat)
        val predicted = id?.let { actingChance.get()?.invoke(it, attack, state) }
        val lands = predicted?.coerceIn(0.0, 1.0) ?: withoutPrediction
        // One that falls at the turn's end anyway is worth only the hit a knockout first stops (Denial.fallsAnyway).
        val kept = if (denial.fallsAnyway) 0.0 else worth * lands
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("knockout:${attack.actionId}>$id", mapOf("worth" to worth, "first" to first, "threat" to threat, "lands" to lands, "landsWithoutPrediction" to withoutPrediction,
            "predicted" to jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.flag(predicted != null), "fallsAnyway" to jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.flag(denial.fallsAnyway), "denial" to denial.value, "total" to kept + denial.value * first.coerceIn(0.0, 1.0) * tuning.boardToScore))
        return kept + denial.value * first.coerceIn(0.0, 1.0) * tuning.boardToScore
    }

    private const val UNKNOWN_ORDER = 0.5

    /** A knockout of [id] in score units: its worth, or the flat knockout value without one. */
    fun knockoutScore(id: UUID?, tuning: LocalDecisionTuning): Double =
        id?.let(::of)?.let { it * tuning.boardToScore } ?: tuning.knockoutMaterialScore

    fun set(worths: Map<UUID, Double>?, denials: Map<UUID, jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.Denial>? = null) {
        active.set(worths)
        actingChance.remove()
        this.denials.set(denials)
        last = worths.orEmpty()
    }

    /**
     * The chance [id]'s best hit knocks out [attacker] this turn ([jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.Denial.threatOn]),
     * or any one in front without [attacker]; 1 unread.
     */
    fun threatTo(id: UUID?, attacker: UUID? = null): Double = threatOrNull(id, attacker) ?: 1.0

    /** The same, null when the decision has no reading of [id]. */
    fun threatOrNull(id: UUID?, attacker: UUID? = null): Double? = id?.let { denials.get()?.get(it) }?.let { denial ->
        attacker?.let { denial.threatOn[it] } ?: denial.threat
    }

    fun clear() {
        active.remove()
        actingChance.remove()
        denials.remove()
    }
}
