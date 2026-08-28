package jbro.cobblemon.morebattlecontent.betterai.evaluation

/**
 * Every tunable weight used by the local decision path, in one place and in one unit.
 *
 * ## Units
 *
 * The whole local path speaks **board units scaled by 100** ("score units"). One board point is one
 * full HP bar of one Pokemon; a living Pokemon is worth [LIVING_POKEMON_VALUE] board points on top of
 * its remaining HP. So `100.0` score is "one full HP bar" and `200.0` score is "one Pokemon removed
 * from play, ignoring the HP it still had".
 *
 * Nothing in the local path may introduce a weight that is not expressible that way. Before this type
 * existed the path mixed four incompatible scales in a single comparison value:
 *
 * - HP-fraction pressure (`0..100`),
 * - raw move power (`0..300+`),
 * - type-chart multipliers (`0..4`),
 * - hand-picked switch constants.
 *
 * Comparing those with one absolute regret gap is what produced resisted-move picks and pointless
 * switches. Keep new weights in board units or do not add them.
 *
 * [LEGACY] reproduces the pre-fix behaviour exactly so the two can be measured head to head.
 */
internal data class LocalDecisionTuning(
    val id: String,

    // ---- shared board-unit anchors -------------------------------------------------------------
    /** Board value of a Pokemon still being in play, on top of its remaining HP fraction. */
    val livingPokemonValue: Double = 2.0,
    /** Score units per board point. */
    val boardToScore: Double = 100.0,

    // ---- lookahead trust ------------------------------------------------------------------------
    /**
     * Floor on how much of the recursive search result survives when the opponent has not revealed a
     * full move set.
     *
     * The legacy gate multiplied the search result by `(revealed / 4)^2` per unrevealed opponent,
     * which is `0.0` on turn one and `0.0625` after a single revealed move. That did not make the AI
     * cautious, it silently replaced a full turn-by-turn projection with the flat heuristic. A search
     * over the moves that *are* known is still evidence; it is just not the whole picture.
     */
    val lookaheadCoverageFloor: Double = 0.35,
    /** When true, coverage decays linearly in revealed fraction instead of quadratically. */
    val lookaheadLinearCoverage: Boolean = true,
    val maximumLookaheadAdjustment: Double = 800.0,
    /**
     * How much of the value judgement belongs to the search rather than to the immediate heuristic.
     *
     * Zero leaves the heuristic deciding and the search advising, which is what shipped and what was
     * measured to waste the search entirely: it reaches a different conclusion in 57-72% of positions
     * and moves the final answer in 10-17.5%. One hands the value half over completely - the search
     * supplies the board judgement and the heuristic keeps only what a board search cannot re-derive,
     * which is its statements about the candidate itself.
     *
     * Expressed as a weight rather than a switch so the exchange rate can be swept and chosen by
     * measurement. Penalties, collateral, mechanic cost and strategy alignment are never scaled by it.
     */
    val searchAuthority: Double = 0.0,
    /**
     * Board value of a one-HP-bar-per-turn pressure advantage in the search's leaf evaluation.
     *
     * These three were private constants in the leaf. They are here because handing the search the
     * decision made them load-bearing: while the heuristic decides, the leaf only nudges a ranking
     * and a rough weight is harmless; when the search decides, this ratio *is* the AI's character.
     * Material is worth 1.0 per bar and a living Pokemon 2.0, so 0.30 says positional advantage is
     * worth roughly a third of the health it threatens - a number nothing has ever tested.
     */
    val leafPressureWeight: Double = 0.30,
    /** Board value of a certain knockout threat in the leaf, beyond the damage it represents. */
    val leafKnockoutPressure: Double = 0.35,
    /** Board value of moving first, when the public speed order is resolvable. */
    val leafSpeedControlValue: Double = 0.15,
    /**
     * How much more willing the search is to abandon a continuation, in board units.
     *
     * Zero ships, and stays zero. Pruning harder was asked for, built, measured, and rejected.
     *
     * The pruner stops a branch only once the turn has already lost close to a full health bar, which
     * is why it fires on about one node in two thousand. The worry was that raising it would cut the
     * branches where a weak-looking move pays off later - a Speed drop, a Protect - because a small
     * immediate delta is exactly what those look like on the turn they are played. That worry was
     * wrong: at an offset of 0.85 the pruner abandons 179 times as many branches and all three
     * foresight positions are still solved. Those moves chip the target, so their delta never
     * approaches the threshold.
     *
     * What it does not do is save any work. Total nodes went from 5.47M to 5.83M. The total is set by
     * the node and time budget, not by the pruner: freeing work inside the allowance only lets
     * iterative deepening spend it further down. So the real trade is depth against breadth, and
     * played out at an offset of 0.70 it loses - 51 wins to 58 across 120 battles, a 46.8% share.
     *
     * Searching wider beats searching deeper here. Reducing server cost means reducing the budget
     * itself; no amount of pruning will do it.
     */
    val branchPruneThresholdOffset: Double = 0.0,
    /**
     * How much of a tier's worst-case weight applies to turn orders the stat ranges leave open.
     *
     * The opponent chooses their move; they do not choose their IVs. An order the public Speed range
     * cannot resolve is an unknown, not a decision, and the search used to collapse it by a flat
     * minimum - full pessimism at every tier, harsher than the treatment the opponent's actual choices
     * get. Every fixture hid it by handing the search a point range for the opponent, which made the
     * order known and the minimum a no-op; against the roughly 1.8x-wide species range production
     * really supplies, the ranges overlap nearly always and the AI assumed it moved second everywhere.
     *
     * 1.0 keeps the tier weight, 0.0 averages the orders outright. The value is a scale rather than a
     * weight so the difficulty ladder still means something: whatever a Boss's caution is set to, this
     * says how much of it a coin flip deserves.
     *
     * Zero ships. Swept against the foresight positions in the information regime production actually
     * has, the scale is monotone: 1.00 and 0.75 solve one of three, 0.50 and 0.25 solve two, and 0.00
     * solves all three. Played out it is flat - 48.2% over 120 battles at 0.00 and 48.7% at 0.50
     * against the old reading, both inside the noise of a sample that size, which is the expected
     * answer rather than a disappointing one: these positions are rare enough in random battles that
     * win rate has already been shown blind to them.
     *
     * The argument for a middle value is that the uncertainty is correlated - an opponent who is
     * genuinely faster is faster every turn, not re-rolled per node, so averaging under-weights the
     * world where they simply outspeed you. It is a real effect and it is what the tier weight on the
     * opponent's *choices* still covers. Paying for it twice is what produced an AI that could only
     * ever be greedy.
     */
    val turnOrderPessimismScale: Double = 0.0,
    /**
     * How much of a knockout's value depends on landing it before the reply.
     *
     * `actsFirstProbability` sat in the contract unfilled, so the root ranking had no notion of turn
     * order at all and the whole subject lived inside the search - which at the lowest tier is one ply
     * and discounted twice over. Filling the field is a bug fix. Spending it is a design change, and
     * design changes here get measured before they ship.
     *
     * At 1.0 a knockout the trainer is certain to move second for is worth nothing, which is too
     * strong: moving second and knocking out still wins the exchange whenever the reply does not kill.
     * The value is a scale so the sweep can find where between the two the AI actually plays better.
     *
     * Zero ships, and the sweep is why. Every weight from 0.00 to 1.00 solves all three foresight
     * positions and lands inside the noise when played out - 50.0%, 50.0%, 46.8%, 52.6%, 52.6% over 80
     * battles each, against a zero-versus-zero control that returned exactly 50.0%. The harness was
     * measuring the knob and the knob does nothing.
     *
     * That reads as a sound intuition already paid for. The search rolls the turn out, so it already
     * sees that a knockout landed second can be answered and one landed first cannot. Pricing the same
     * fact again in the ranking is double counting, and double counting a true thing still makes the
     * numbers wrong. Filling `actsFirstProbability` was the fix; spending it here was not.
     */
    val firstStrikeWeight: Double = 0.0,

    // ---- damage ---------------------------------------------------------------------------------
    /**
     * Move power that corresponds to one full HP bar when no Showdown projection is available.
     *
     * Used only to keep an unprojectable move on the HP-fraction scale. The legacy path fell back to
     * raw `power * accuracy * stab * typeMultiplier`, which is a `0..300` number compared directly
     * against `0..100` HP-fraction pressure - a 120 BP resisted move scored `90` against a projected
     * super-effective move scoring `60`, so the resisted move won.
     *
     * Derived from the Gen 9 formula rather than picked. At level 50 with even offence and defence the
     * base damage is `((2 * 50 / 5 + 2) * power * atk / def) / 50 + 2`, which is `0.44 * power + 2`,
     * against a level 50 max HP around 165. One full bar is therefore roughly `power 340` neutral and
     * unboosted; STAB and type effectiveness are applied separately as multipliers, so this constant
     * must stay the *neutral* figure. 300 keeps a small margin for the fact that an unprojectable move
     * is more often a mechanic-boosted or spread move than a plain one.
     */
    val unprojectedPowerPerHpBar: Double = 300.0,
    /**
     * When true, an unprojectable move keeps the legacy `power * accuracy * stab * typeMultiplier`
     * fallback instead of being converted to an HP fraction. Only [LEGACY] sets this.
     */
    val legacyRawPowerFallback: Boolean = false,

    // ---- knockouts ------------------------------------------------------------------------------
    /**
     * Score credited for removing a Pokemon from play, on top of the HP damage already counted.
     *
     * Must stay equal to `livingPokemonValue * boardToScore`. The legacy path added a flat `250` at
     * ranking time *and* up to `50` more from the situational evaluator, on top of damage pressure
     * that already contained the target's remaining HP - so a guaranteed knockout was worth up to
     * `300` when the board says it is worth `200`.
     */
    val knockoutMaterialScore: Double = 200.0,

    // ---- switching ------------------------------------------------------------------------------
    /** Cost of spending a turn switching instead of acting. */
    val switchTempoPenalty: Double = 20.0,
    /** Weight on the incoming Pokemon's HP advantage over the outgoing one. */
    val switchHealthAdvantageWeight: Double = 40.0,
    /**
     * Weight on reduced incoming damage per turn.
     *
     * Exposure is now always "expected fraction of the defender's HP removed per turn", so one unit of
     * improvement is one full HP bar saved per turn and this weight is a score-unit conversion.
     */
    val switchExposureImprovementWeight: Double = 100.0,
    val switchExposureWorseningWeight: Double = 100.0,
    val switchOffensivePressureWeight: Double = 40.0,
    val switchInitiativeWeight: Double = 12.0,
    val criticalSwitchBonus: Double = 60.0,
    val publicKnockoutThreatSwitchBonus: Double = 25.0,
    /** Settling cost charged against the incoming Pokemon's own exposure, per exposure unit. */
    val residualExposureWeight: Double = 25.0,

    // ---- exposure -------------------------------------------------------------------------------
    /**
     * HP fraction a neutral (1x) hit is assumed to remove when no opponent move has been revealed.
     *
     * This is what puts the type-chart estimate and the revealed-move estimate on the same scale.
     * Legacy returned the raw type multiplier (`0..4`) in one branch and expected HP damage
     * (`0..~1.4`) in the other, from the same function, into the same subtraction.
     */
    val neutralHitHpFraction: Double = 0.25,
    /** Lower bound on exposure so `hp / exposure` stays finite against immunities. */
    val immunityExposureFloor: Double = 0.125,
    /**
     * Ceiling on `survivalPosition`, in turns.
     *
     * Survival is only ever compared against switch thresholds, and beyond a handful of turns the
     * difference between "safe" and "safer" stops being a reason to spend the turn switching.
     *
     * It also decides where the repeated-switch schedule closes, so it is solved rather than picked.
     * Against a full-HP immunity (`1.0 / 0.125`, capped) the requirement `3.5 + 2 * (switches - 1)`
     * has to still admit a second switch that escapes a 2x matchup at 80% HP - which needs a cap of
     * at least `5.5 + 1.6 = 7.1` - while refusing a third switch out of the same matchup at 15% HP,
     * which needs a cap below `7.5 + 0.3 = 7.8`. Anything in `[7.1, 7.8)` satisfies both; the midpoint
     * keeps the margin on either side.
     */
    val maximumSurvivalTurns: Double = 7.5,

    // ---- switch gating (survival turns) ---------------------------------------------------------
    /**
     * Survival-turn gain a switch must show to avoid being demoted to the tempo-loss tier.
     *
     * These are stated in the same turns unit as `survivalPosition`. They previously sat on the raw
     * `hp / typeMultiplier` quotient, so changing the exposure unit without moving them turned every
     * gate wide open - which is the precise mechanism by which fixing one unit bug manufactures a
     * worse one downstream.
     */
    val materialSurvivalTurnGain: Double = 1.5,
    /** Higher bar when the outgoing Pokemon was itself brought in within the last turn. */
    val recentSwitchSurvivalTurnGain: Double = 3.5,
    /** Additional turns demanded per switch already spent this battle. */
    val repeatedSwitchTurnGainStep: Double = 2.0,
    /**
     * When true, `defensiveExposure` keeps returning a raw type-chart multiplier (`0..4`) when no
     * opponent move has been revealed and expected HP damage (`0..~1.4`) once one has - two different
     * quantities from one function, fed into one subtraction. Only [LEGACY] sets this.
     */
    val legacyMixedExposureUnits: Boolean = false,

    // ---- mixing ---------------------------------------------------------------------------------
    /** Top fraction of legal actions that may receive exploratory probability. */
    val shortlistFraction: Double = 0.40,
    /**
     * Regret gap as a fraction of the best action's *own* magnitude, at zero risk tolerance.
     *
     * A flat `45..80` score gap means something different on every turn. When the best action is
     * worth two and a half health bars it is a tight 18% window; when the turn is worth a fifth of a
     * bar it is 225%, and literally every legal action lands in the mixing pool. Expressing the window
     * as a fraction of the decision's own size makes "close enough to be worth mixing" mean one thing.
     *
     * The fractions are chosen to reproduce the established window at the scale it was tuned for -
     * around one health bar - and to tighten only where the flat number was absurd.
     */
    val relativeRegretGap: Double = 0.45,
    /** Same fraction at full risk tolerance; risk widens the window, it does not change its meaning. */
    val relativeRegretGapAtHighRisk: Double = 0.80,
    /** Absolute floor on the regret gap so near-zero-value turns still mix. */
    val minimumRegretGapScore: Double = 12.0,
    /** Absolute ceiling regardless of the best action's magnitude. */
    val maximumRegretGapScore: Double = 80.0,
    val maximumReasonableScoreGap: Double = 199.0,

    // ---- abilities ------------------------------------------------------------------------------
    /** Apply publicly revealed defensive abilities to the type-chart multiplier. */
    val applyRevealedDefensiveAbilities: Boolean = true,
) {
    init {
        require(lookaheadCoverageFloor in 0.0..1.0)
        require(searchAuthority.isFinite() && searchAuthority in 0.0..1.0)
        require(leafPressureWeight.isFinite() && leafPressureWeight >= 0.0)
        require(leafKnockoutPressure.isFinite() && leafKnockoutPressure >= 0.0)
        require(leafSpeedControlValue.isFinite() && leafSpeedControlValue >= 0.0)
        require(unprojectedPowerPerHpBar > 0.0)
        require(shortlistFraction > 0.0 && shortlistFraction <= 1.0)
        require(relativeRegretGap >= 0.0)
        require(neutralHitHpFraction > 0.0)
        require(immunityExposureFloor > 0.0)
        require(maximumSurvivalTurns > 0.0)
    }

    /** Board points converted to comparison score. */
    fun board(points: Double): Double = points * boardToScore

    companion object {
        /** Current, unit-consistent weights. */
        /**
         * `searchAuthority` stays at 0.0. The inversion was built, measured, and rejected.
         *
         * Handing the value judgement to the search does change the answer, sharply and
         * non-linearly: partial authority buys almost nothing because the two terms are correlated
         * and a blend still points the same way, while full withdrawal moves the answer in 40% of
         * recorded positions and differs from the immediate heuristic in 52.5%. That cleared the
         * target this work set for itself.
         *
         * It also plays worse. Head to head against itself with the setting off - the only comparison
         * that isolates it - the search-led side won 47 of 113 decided battles, a 41.6% share over
         * 120 games. Every other reading looked like an improvement: more decisive battles (95.0%
         * against 91.7%), fewer stalls, noticeably more status and utility moves (4.68 against 3.77
         * per battle). It simply lost.
         *
         * The reason is not that the inversion is wrong in principle - depth is still computed and
         * discarded - but that the leaf evaluator is not yet good enough to be handed the decision.
         * It scores a board as material, best available attack pressure and a speed term, while the
         * heuristic it would replace knows about knockout certainty, priority, spread damage, status
         * pressure and switch survival. Withdrawing the richer of the two in favour of the cruder one
         * loses more than the extra depth returns.
         *
         * The knob and `LocalSearchAuthoritySweepTest` stay so the question can be reopened once the
         * leaf evaluator has been given what it is missing. Raising this without doing that first
         * will reproduce the same result.
         */
        val CURRENT = LocalDecisionTuning(id = "current")

        /**
         * Exact pre-fix behaviour, kept so regressions and improvements can be measured head to head
         * instead of asserted. Do not ship this as the active tuning.
         */
        val LEGACY = LocalDecisionTuning(
            id = "legacy",
            lookaheadCoverageFloor = 0.0,
            lookaheadLinearCoverage = false,
            legacyRawPowerFallback = true,
            knockoutMaterialScore = 250.0,
            switchExposureImprovementWeight = 50.0,
            switchExposureWorseningWeight = 50.0,
            residualExposureWeight = 10.0,
            neutralHitHpFraction = 1.0,
            legacyMixedExposureUnits = true,
            immunityExposureFloor = 0.25,
            maximumSurvivalTurns = Double.MAX_VALUE,
            materialSurvivalTurnGain = 0.25,
            recentSwitchSurvivalTurnGain = 0.75,
            repeatedSwitchTurnGainStep = 2.0,
            relativeRegretGap = 0.0,
            minimumRegretGapScore = 45.0,
            maximumRegretGapScore = 80.0,
            applyRevealedDefensiveAbilities = false,
        )
    }
}
