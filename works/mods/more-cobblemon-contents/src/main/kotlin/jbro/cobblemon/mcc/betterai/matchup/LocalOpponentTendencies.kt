package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.policy.LocalBattleMind
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseModel
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSituation

/**
 * The opponent intents leaned toward the opponent's own record: how often it moved and how often it switched in the
 * situation the board is in, this battle and the run before it ([jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView.tendencies],
 * read through [LocalOpponentResponseModel.observedRates]). The situations are those any of this side's actions sets up
 * (a credible knockout, moving first, a mechanic, the opponent low or boosted), as the opponent sees them on the board;
 * the one that depends on the pair of targets this side picks (DOUBLE_FOCUS_TARGET) is left out.
 *
 * Each intent's switch chance moves by [weight] times the record's pull: the record's evidence (none below three answers
 * in that situation, all of it from a recent weight of eight) times the gap to the recorded switch share. [weight] is a
 * plain multiplier: 1.0 takes the pull as it is, 1.2 a fifth more (past the recorded share if it comes to that), 0 none;
 * only the resulting chance is kept within 0..1. The moves keep their split among themselves, and so do the switches.
 * An intent offering no switch (locked in, trapped, nothing left) or no move stays as it is.
 */
internal object LocalOpponentTendencies {
    fun apply(intents: List<OpponentIntent>, context: BattleDecisionContext, weight: Double): List<OpponentIntent> {
        if (weight <= 0.0 || intents.isEmpty()) return intents
        val situations = context.candidates
            .flatMapTo(LinkedHashSet()) { LocalBattleMind.situations(context.state, it) }
            .minus(BattleSituation.DOUBLE_FOCUS_TARGET)
        val rates = LocalOpponentResponseModel.observedRates(context.memory, situations) ?: return intents
        val recorded = rates.move + rates.switch
        if (recorded <= 0.0 || !recorded.isFinite()) return intents
        val recordedSwitch = rates.switch / recorded
        val pull = weight * rates.evidence
        if (pull <= 0.0) return intents
        return intents.map { intent ->
            val switches = intent.options.filter { it.kind == IntentKind.SWITCH }.sumOf { it.probability }
            val moves = intent.options.filter { it.kind != IntentKind.SWITCH }.sumOf { it.probability }
            val total = switches + moves
            if (switches <= 0.0 || moves <= 0.0) return@map intent
            val share = switches / total
            val leaned = (share + pull * (recordedSwitch - share)).coerceIn(0.0, 1.0)
            val switchScale = leaned / share
            val moveScale = (1.0 - leaned) / (1.0 - share)
            intent.copy(options = intent.options.map { option ->
                option.copy(probability = option.probability * if (option.kind == IntentKind.SWITCH) switchScale else moveScale)
            }.sortedByDescending { it.probability })
        }
    }
}
