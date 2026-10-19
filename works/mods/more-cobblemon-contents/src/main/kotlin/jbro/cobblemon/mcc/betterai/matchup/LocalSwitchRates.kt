package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * How often opposing Pokemon actually switch out, counted from play instead of fixed weights.
 *
 * The switch model's logistic weights were fitted once on Boss self-play, and the AI's own switching changed under
 * them: in the league runs of 2026-10-19 a Pokemon about to be knocked out before it moved switched 73% of the time
 * while the model said 19%, and over all positions the model scored worse than the plain average (Brier 0.226
 * against 0.212). Counted rates replayed over the same 87 games scored 0.134-0.140, and 0.131 over the first ten.
 *
 * Two situations are counted, weighted by [LocalOpponentIntentPredictor.SwitchFeatures.threatened], the chance the
 * Pokemon falls before it acts: a threatened one, and a calm one. Each is counted twice: across every battle this
 * server's AI has read ([GLOBAL], process-wide, so it follows whoever the AI faces now) and within this battle, for
 * this trainer. A rate is this battle's count shrunk toward the global rate, which is shrunk toward the fitted model
 * until there is play to go on - the model is only the cold start.
 *
 * A position counts once its outcome is public: a move used is a stay, a switch into its slot is a switch, and a
 * faint or nothing public counts as neither (a replacement after a knockout is no choice).
 */
internal class LocalSwitchRates {
    private val battle = Tally()
    private var pendingTurn = -1
    private var pending: Map<UUID, Pair<Int, Double>> = emptyMap()

    /** Counts what the opposing Pokemon remembered on the turn last decided did, once that turn is over. */
    @Synchronized
    fun observe(state: BattleStateView) {
        if (pendingTurn < 0 || state.turn <= pendingTurn) return
        for ((pokemonId, remembered) in pending) {
            val (slot, threatened) = remembered
            val action = LocalOpponentRepeats.actionOn(state, pendingTurn, pokemonId, slot) ?: continue
            val switched = action.startsWith(LocalOpponentRepeats.SWITCH)
            battle.add(threatened, switched)
            GLOBAL.add(threatened, switched)
        }
        pendingTurn = -1
        pending = emptyMap()
    }

    /** Remembers this turn's opposing Pokemon that could switch, with how threatened each is, to count next turn. */
    @Synchronized
    fun remember(state: BattleStateView, threatened: Map<UUID, Double>) {
        val bench = state.remainingPokemonBySide[BattleSide.OPPONENT] ?: return
        pendingTurn = state.turn
        pending = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                bench > state.pokemon.count { other -> other.side == BattleSide.OPPONENT && other.activeSlot != null && !other.fainted }
        }.associate { it.battlePokemonId to (requireNotNull(it.activeSlot) to (threatened[it.battlePokemonId] ?: 0.0)) }
    }

    /** The chance a Pokemon this threatened switches out, from this battle and every battle before, [cold] to start. */
    @Synchronized
    fun chance(threatened: Double, cold: Double): Double {
        val t = threatened.coerceIn(0.0, 1.0)
        return t * rate(true, cold) + (1.0 - t) * rate(false, cold)
    }

    private fun rate(threatenedSide: Boolean, cold: Double): Double {
        val global = GLOBAL.rate(threatenedSide, cold, GLOBAL_STRENGTH)
        return battle.rate(threatenedSide, global, BATTLE_STRENGTH)
    }

    /** Switches and positions per situation, a position split between them by its threat. */
    internal class Tally {
        private var threatenedSwitches = 0.0
        private var threatenedPositions = 0.0
        private var calmSwitches = 0.0
        private var calmPositions = 0.0

        @Synchronized
        fun add(threatened: Double, switched: Boolean) {
            val t = threatened.coerceIn(0.0, 1.0)
            val s = if (switched) 1.0 else 0.0
            threatenedPositions += t; threatenedSwitches += t * s
            calmPositions += 1.0 - t; calmSwitches += (1.0 - t) * s
        }

        /** The counted rate shrunk toward [prior] as if [strength] positions had shown it. */
        @Synchronized
        fun rate(threatenedSide: Boolean, prior: Double, strength: Double): Double =
            if (threatenedSide) (threatenedSwitches + strength * prior) / (threatenedPositions + strength)
            else (calmSwitches + strength * prior) / (calmPositions + strength)
    }

    companion object {
        /** Every battle this server's AI has read, since it started. */
        val GLOBAL = Tally()
        /**
         * How many positions the prior counts as. Not fitted to behaviour: how fast play overrides the start. Replayed
         * over the 87 league games, 2-8 for the battle and 5-20 for the global rate all landed within 0.013 Brier.
         */
        const val BATTLE_STRENGTH = 8.0
        const val GLOBAL_STRENGTH = 5.0
    }
}
