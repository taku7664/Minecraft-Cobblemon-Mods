package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Positions where the immediately weaker action is the right one.
 *
 * Every case has the same shape and a different mechanism: this turn gives something up, and the turn
 * after next pays it back. Lower the opponent's Speed and win the order. Heal out of the range that
 * would have killed you. Boost, then finish. Burn the attacker and halve what it hits for. Raise
 * special defence and turn a losing race into a winning one.
 *
 * The family is deliberate. A single scenario can be passed by special-casing it, and a fix that only
 * moves one number is not the capability being asked for - which is that the AI weighs what happens
 * next, whatever produced it. Five mechanisms sharing one shape means a fix either reaches the general
 * path or it shows up as four failures.
 *
 * Nothing here asserts a correct answer yet. This is the baseline: what the AI does today, and how far
 * the search's voice is from being able to change it.
 */
class LocalForesightScenarioTest {
    @Test
    fun `positions needing foresight are reported for every tier`() {
        val scenarios = listOf(
            speedDrop(),
            healOutOfRange(),
            protectAndLeftovers(),
            setupThenFinish(),
            burnTheAttacker(),
            specialDefenceBoost(),
        )
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )

        var solved = 0
        val report = buildString {
            appendLine("=".repeat(112))
            appendLine("FORESIGHT SCENARIOS  ${scenarios.size} mechanisms, one shape")
            appendLine("=".repeat(112))
            appendLine("Each position offers a greedy action and a patient one. The patient action is the")
            appendLine("right one, and only the turn after next says so.")
            appendLine()
            scenarios.forEach { scenario ->
                val context = PublicBattleTacticalCalculator.calculate(scenario.context)
                appendLine("-- ${scenario.name} --")
                appendLine("   ${scenario.rationale}")
                context.candidates.forEach { candidate ->
                    val facts = candidate.facts
                    appendLine(
                        "   %-14s damage=%-14s ko=%s".format(
                            candidate.actionId,
                            facts?.standardDamageFractionRange?.let {
                                String.format("%.3f-%.3f", it.minimum, it.maximum)
                            } ?: "none",
                            facts?.standardKnockoutAssessment?.name ?: "-",
                        ),
                    )
                }
                var solvedHere = false
                tiers.forEach { (tierName, difficulty) ->
                    val breakdown = LocalDecisionInstrumentation.inspect(
                        context = context,
                        profile = BattleTrainerProfile(
                            skillLevel = 2,
                            personality = BattleTrainerProfile.champion().personality,
                            difficulty = difficulty,
                        ),
                        tuning = LocalDecisionTuning.CURRENT,
                    )
                    val production = breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId
                    val search = breakdown.candidates.maxByOrNull { it.lookaheadUtility }?.actionId
                    val patient = breakdown.candidates.firstOrNull { it.actionId == scenario.patientAction }
                    val greedy = breakdown.candidates.firstOrNull { it.actionId == scenario.greedyAction }
                    val heuristicGap = (greedy?.heuristicOnlyValue ?: 0.0) - (patient?.heuristicOnlyValue ?: 0.0)
                    val searchGap = (patient?.lookaheadUtility ?: 0.0) - (greedy?.lookaheadUtility ?: 0.0)
                    if (production == scenario.patientAction) solvedHere = true
                    appendLine(
                        "   %-13s production=%-13s search=%-13s greedy ahead by %8.1f, search offers %8.1f".format(
                            tierName, production ?: "-", search ?: "-", heuristicGap, searchGap,
                        ),
                    )
                }
                if (solvedHere && scenario.withinSearchHorizon) solved++
                if (!scenario.withinSearchHorizon) {
                    appendLine("   (payoff lands past the search horizon - this is plan work, not search work)")
                }
                appendLine()
            }
            val reachable = scenarios.count { it.withinSearchHorizon }
            appendLine("SOLVED: $solved of $reachable reachable   (any tier choosing the patient action counts)")
            appendLine("        ${scenarios.size - reachable} more need a plan held across turns, not a deeper tree.")
            appendLine()
            appendLine("The last two columns are the whole story. The search has to out-argue the heuristic")
            appendLine("by the first number, and is only offering the second.")
        }
        println(report)

        // The capability this work was asked for, as a gate rather than a report. Every position whose
        // payoff lands inside the search horizon has to keep being solved - the Speed drop, the heal,
        // and Protect with Leftovers. The three that land outside are deliberately excluded: two are
        // ties and one is a position where declining is correct, so requiring them would be requiring
        // the AI to play worse.
        val reachable = scenarios.count { it.withinSearchHorizon }
        assertEquals(reachable, solved, report)
    }

    @Test
    fun `an active plan is measured for whether it can reach the long-horizon cases`() {
        // The long-horizon scenarios were handed to plan persistence. Before building anything there,
        // the cheap question: can a plan that already exists reach them at all?
        //
        // Two reasons to doubt it. The intent vocabulary has nothing for "invest now, cash in later" -
        // it aligns with attacking, switching, or establishing a field, and a self-boost or a status
        // is none of those. And alignment is a multiplier on the selector's draw, not a term in the
        // score, which is exactly where risk attitude was found to be inert before it was moved.
        val scenarios = listOf(setupThenFinish(), burnTheAttacker(), specialDefenceBoost())
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("PLANS AGAINST THE LONG-HORIZON CASES")
            appendLine("=".repeat(100))
            scenarios.forEach { scenario ->
                appendLine("-- ${scenario.name} --  patient action is ${scenario.patientAction}")
                (listOf<BattlePlanIntent?>(null) + BattlePlanIntent.entries).forEach { intent ->
                    val planned = withPlan(scenario.context, intent)
                    val breakdown = LocalDecisionInstrumentation.inspect(
                        context = PublicBattleTacticalCalculator.calculate(planned),
                        profile = profile,
                        tuning = LocalDecisionTuning.CURRENT,
                    )
                    val chosen = breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId
                    appendLine(
                        "   plan=%-18s production=%-14s %s".format(
                            intent?.name ?: "(none)", chosen ?: "-",
                            if (chosen == scenario.patientAction) "<-- reached" else "",
                        ),
                    )
                }
                appendLine()
            }
            appendLine("If no intent reaches any of them, plans as they stand cannot deliver this and the")
            appendLine("work is a new intent plus a route into the score, not tuning what is here.")
        }
        println(report)

        assertTrue(scenarios.isNotEmpty(), report)
    }

    @Test
    fun `leaf pressure weight is swept against every foresight position`() {
        // The signal the long-horizon cases need is already computed. A boost raises the attacker's
        // own projected pressure in the state it produces, and the leaf evaluator measures exactly
        // that, for both sides, without asking what kind of move caused it.
        //
        // It is then multiplied by 0.30. Pressure tops out around one health bar, so the entire
        // positional term is worth at most 0.30 board - 30 points - against a 42.7 point preference
        // for attacking. The knob was swept once before against win rate and did nothing; win rate
        // has since been shown blind to exactly this kind of position, so it is worth asking again
        // with the positions themselves.
        val scenarios = listOf(
            speedDrop(), healOutOfRange(), setupThenFinish(), burnTheAttacker(), specialDefenceBoost(),
        )
        val weights = listOf(0.30, 0.60, 1.00, 1.50, 2.50)
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("LEAF PRESSURE WEIGHT AGAINST THE FORESIGHT POSITIONS  tier=boss")
            appendLine("=".repeat(104))
            appendLine("0.30 is what ships. Higher values price a position that has not paid off yet.")
            appendLine()
            weights.forEach { weight ->
                val tuning = LocalDecisionTuning.CURRENT.copy(id = "p$weight", leafPressureWeight = weight)
                val chosen = scenarios.map { scenario ->
                    val breakdown = LocalDecisionInstrumentation.inspect(
                        context = PublicBattleTacticalCalculator.calculate(scenario.context),
                        profile = profile,
                        tuning = tuning,
                    )
                    val best = breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId
                    scenario to (best == scenario.patientAction)
                }
                appendLine(
                    "  weight=%.2f  solved=%d/%d   %s".format(
                        weight,
                        chosen.count { it.second },
                        scenarios.size,
                        chosen.joinToString(" ") { (scenario, ok) ->
                            "${scenario.name.take(9)}=${if (ok) "yes" else "no"}"
                        },
                    ),
                )
            }
            appendLine()
            appendLine("Read the last three columns as 'changed', not as 'improved'.")
            appendLine()
            appendLine("Only the two reachable positions have a patient answer that is demonstrably")
            appendLine("better. The defensive boost loses either way - the boost buys one turn and the")
            appendLine("target still survives - so choosing it at a high weight is the AI getting worse.")
            appendLine("The setup position is an exact tie: two unboosted attacks and one boosted attack")
            appendLine("both knock the target out on turn two, from the same health, having taken the same")
            appendLine("hit. Preferring either is a tie-break, not foresight.")
            appendLine()
            appendLine("So this sweep buys nothing it can show. The knob moves behaviour and the only")
            appendLine("position where the movement is checkable is one where it moves the wrong way.")
        }
        println(report)

        assertTrue(weights.isNotEmpty(), report)
    }

    private fun withPlan(context: BattleDecisionContext, intent: BattlePlanIntent?): BattleDecisionContext =
        BattleDecisionContext(
            requestId = context.requestId,
            state = context.state,
            candidates = context.candidates,
            deadlineEpochMillis = context.deadlineEpochMillis,
            memory = if (intent == null) {
                BattleTacticalMemoryView.empty()
            } else {
                BattleTacticalMemoryView(
                    activePlan = BattlePlanView(intent = intent, expiresAtTurn = context.state.turn + 4),
                    activePlanOwner = BattlePlanOwner.LOCAL_BRAIN,
                    turnsSinceLastSwitch = 3,
                )
            },
            publicActionCatalog = context.publicActionCatalog,
        )

    private class Scenario(
        val name: String,
        val rationale: String,
        val context: BattleDecisionContext,
        val greedyAction: String,
        val patientAction: String,
        /**
         * Whether the payoff lands inside the depth a search is given.
         *
         * Turn order and survival resolve on the very next turn, so two plies can see them. A boost or
         * a burn pays back over the third attack and later - two unboosted hits and one boosted hit
         * take the same two turns, and the gain only appears on the turn after that. No allowance this
         * project grants would reach it, so a search declining them is correct rather than blind, and
         * scoring it as failure would push a fix in the wrong direction.
         *
         * Those belong to plan persistence: "boost now, sweep in three" is an intention held across
         * turns, not a tree result.
         */
        val withinSearchHorizon: Boolean = true,
    )

    /** Take their Speed, move first from now on, land the finisher before it can be answered. */
    private fun speedDrop() = Scenario(
        name = "speed drop",
        rationale = "Blizzard hits harder now; Icy Wind takes the turn order and the next exchange.",
        greedyAction = "blizzard",
        patientAction = "icy_wind",
        context = position(
            allyHp = 0.55, allySpeed = 100, opponentHp = 0.90, opponentSpeed = 110,
            candidates = listOf(
                specialMove("blizzard", 110.0),
                specialMove(
                    "icy_wind", 55.0,
                    effects = listOf(statStage(BattleMoveEffectTarget.SELECTED_TARGET, mapOf("speed" to -1))),
                ),
            ),
        ),
    )

    /** Heal past the damage that would have finished you, then win the longer exchange. */
    /**
     * Ally 0.40, faster, taking 0.48 a hit. Blizzard does 0.63 and the target is at 0.90.
     *
     *  greedy   attack (0.90 -> 0.27), then their hit kills outright: 0.40 < 0.48.
     *  patient  heal to 0.90, survive to 0.42, attack twice and finish first.
     *
     * The first version of this had the ally slower, which meant it died before it could heal - the
     * AI was right to refuse and the scenario was wrong.
     */
    private fun healOutOfRange() = Scenario(
        name = "heal out of range",
        rationale = "Attacking dies to the reply; healing survives it and still wins the race.",
        greedyAction = "blizzard",
        patientAction = "recover",
        context = position(
            allyHp = 0.40, allySpeed = 120, opponentHp = 0.90, opponentSpeed = 100,
            candidates = listOf(
                specialMove("blizzard", 110.0),
                statusMove(
                    "recover",
                    listOf(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.HEAL_FRACTION,
                            BattleMoveEffectTarget.USER,
                            probability = 1.0,
                            fractionRange = BattleFractionRange(0.5, 0.5),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Give up a turn of damage for a boost that turns the next attack into a knockout. */
    /**
     * The second position asked for directly: block a turn, let the item heal, and be out of range.
     *
     * Ally 0.45 and faster, holding Leftovers, facing a hit that does 0.48. Blizzard does 0.63 into a
     * target at 0.90, so two are needed.
     *
     *  greedy   T1 attack to 0.27, their hit lands for 0.48 and 0.45 is not enough - faints with the
     *           target alive.
     *  patient  T1 Protect blocks it, Leftovers adds 1/16 to 0.5125 - now above the 0.48 that would
     *           have killed. T2 attack to 0.27 and survive the hit at 0.0325. T3 attack finishes it.
     *
     * The whole difference is one sixteenth of a health bar arriving before the hit rather than after,
     * which is the narrow band that makes the item worth holding at all.
     */
    private fun protectAndLeftovers() = Scenario(
        name = "protect and leftovers",
        rationale = "Blocking one turn and healing a sixteenth puts the defender out of range.",
        greedyAction = "blizzard",
        patientAction = "protect",
        context = position(
            allyHp = 0.45, allySpeed = 120, opponentHp = 0.90, opponentSpeed = 100,
            allyHeldItem = "leftovers",
            candidates = listOf(
                specialMove("blizzard", 110.0),
                statusMove("protect", listOf(effectOf(BattleMoveEffectKind.PROTECT_USER))),
            ),
        ),
    )

    private fun effectOf(kind: BattleMoveEffectKind) = BattleMoveEffectView(
        kind,
        BattleMoveEffectTarget.USER,
        probability = 1.0,
    )

    /**
     * Ally 0.85 and faster, target at full, threat 0.48, Blizzard 0.63, boosted Blizzard 1.26.
     *
     *  greedy   T1 attack to 0.37 and take a hit to 0.37. T2 attack knocks it out.
     *  patient  T1 boost and take a hit to 0.37. T2 boosted attack knocks it out.
     *
     * The same turn, the same health, the same result - an exact tie. The boost only pulls ahead from
     * a third attack onwards, which nothing here reaches. Neither answer is the right one, so this
     * position cannot be used to argue for a change; it is kept to stop a fix claiming a win here.
     */
    private fun setupThenFinish() = Scenario(
        name = "setup then finish",
        rationale = "The boost only repays from the third attack onwards.",
        greedyAction = "blizzard",
        patientAction = "nasty_plot",
        withinSearchHorizon = false,
        context = position(
            allyHp = 0.85, allySpeed = 120, opponentHp = 1.0, opponentSpeed = 100,
            candidates = listOf(
                specialMove("blizzard", 110.0),
                statusMove(
                    "nasty_plot",
                    listOf(statStage(BattleMoveEffectTarget.USER, mapOf("special_attack" to 2))),
                ),
            ),
        ),
    )

    /** Halve what the attacker hits for, and the exchange that was lost becomes won. */
    /**
     * Halving what the attacker deals is worth a whole extra turn of survival only after several of
     * their turns have gone by. Like the boost, the gain arrives past any allowance granted here.
     */
    private fun burnTheAttacker() = Scenario(
        name = "burn the attacker",
        rationale = "Halved output only shows across several of their turns.",
        greedyAction = "blizzard",
        patientAction = "will_o_wisp",
        withinSearchHorizon = false,
        context = position(
            allyHp = 0.50, allySpeed = 100, opponentHp = 0.95, opponentSpeed = 110,
            candidates = listOf(
                specialMove("blizzard", 110.0),
                statusMove(
                    "will_o_wisp",
                    listOf(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.STATUS,
                            BattleMoveEffectTarget.SELECTED_TARGET,
                            probability = 1.0,
                            valueId = "brn",
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Raise special defence so the losing two-hit race becomes a winning one. */
    /**
     * Ally 0.60, slower, taking 0.35 a hit; Blizzard does 0.63 into a target at 0.95.
     *
     *  greedy   T1 hit to 0.25, attack to 0.32. T2 hit for 0.35 - faints, target survives.
     *  patient  T1 hit to 0.25, boost. T2 halved hit to 0.075, attack to 0.32. T3 - faints, target
     *           survives.
     *
     * Both lines lose. The boost buys one more turn and the same damage, and never turns the exchange
     * around, so it is long-horizon like the others: worth something over a long fight, worth nothing
     * inside a tree.
     *
     * Two earlier versions of this were labelled winnable and were not, which is what makes it worth
     * keeping. At two plies the search values it at +192 - it sees "still alive at the end of turn
     * two" and stops. At three and four it sees the ally dying anyway and drops to +12 and +22. That
     * looked like depth making the AI worse and is the opposite: the shallow search was wrong, the
     * deep one is right, and the label was mine.
     */
    private fun specialDefenceBoost() = Scenario(
        name = "special defence boost",
        rationale = "Buys a turn and loses anyway; the deeper search is right to decline.",
        greedyAction = "blizzard",
        patientAction = "amnesia",
        withinSearchHorizon = false,
        context = position(
            threatPower = 60.0,
            allyHp = 0.60, allySpeed = 100, opponentHp = 0.95, opponentSpeed = 110,
            candidates = listOf(
                specialMove("blizzard", 110.0),
                statusMove(
                    "amnesia",
                    listOf(statStage(BattleMoveEffectTarget.USER, mapOf("special_defence" to 2))),
                ),
            ),
        ),
    )

    private fun statStage(target: BattleMoveEffectTarget, stages: Map<String, Int>) = BattleMoveEffectView(
        BattleMoveEffectKind.STAT_STAGE,
        target,
        probability = 1.0,
        statStages = stages,
    )

    private fun position(
        allyHp: Double,
        allySpeed: Int,
        opponentHp: Double,
        opponentSpeed: Int,
        candidates: List<BattleActionCandidate>,
        threatPower: Double = 85.0,
        threatCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
        allyHeldItem: String? = null,
    ): BattleDecisionContext {
        val ally = pokemon(
            BattleSide.ALLY, allySpeed, allyHp, setOf("ice"), specialDefence = 95, heldItem = allyHeldItem,
        )
        val opponent = pokemon(BattleSide.OPPONENT, opponentSpeed, opponentHp, setOf("dragon"), specialDefence = 190)
        val pokemon = listOf(ally, opponent)
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.SINGLE,
                turn = 5,
                pokemon = pokemon,
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                    pokemon.count { it.side == side }
                },
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = candidates,
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            // Both sides, and the ally's own entry is not optional. Future moves are read only from
            // this catalog, for either side - so an ally missing from it can do nothing next turn as
            // far as the search is concerned, and every setup, heal or defensive move scores as pure
            // loss. Production fills the ally in as EXACT_OWN; a fixture that skips it is measuring
            // its own omission.
            publicActionCatalog = BattlePublicActionCatalogView(
                listOf(
                    BattlePokemonActionCatalogView(
                        battlePokemonId = ally.battlePokemonId,
                        moves = candidates.mapNotNull { candidate ->
                            candidate.moveDetails?.let { details ->
                                BattlePublicMoveOptionView(
                                    moveId = requireNotNull(candidate.moveId),
                                    details = details,
                                    knowledge = BattlePublicMoveKnowledge.EXACT_OWN,
                                )
                            }
                        },
                        moveSetComplete = true,
                    ),
                    BattlePokemonActionCatalogView(
                        battlePokemonId = opponent.battlePokemonId,
                        moves = listOf(
                            BattlePublicMoveOptionView(
                                moveId = "cobblemon:dragon_pulse",
                                details = BattleMoveCandidateView(
                                    typeId = "dragon",
                                    damageCategory = threatCategory,
                                    power = threatPower,
                                    accuracy = 100.0,
                                    priority = 0,
                                    currentPp = 10,
                                    targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                                ),
                                knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                            ),
                        ),
                        moveSetComplete = true,
                    ),
                ),
            ),
        )
    }

    private fun specialMove(
        id: String,
        power: Double,
        effects: List<BattleMoveEffectView> = emptyList(),
    ) = candidate(id, power, BattleMoveDamageCategory.SPECIAL, effects)

    private fun statusMove(id: String, effects: List<BattleMoveEffectView>) =
        candidate(id, 0.0, BattleMoveDamageCategory.STATUS, effects)

    private fun candidate(
        id: String,
        power: Double,
        category: BattleMoveDamageCategory,
        effects: List<BattleMoveEffectView>,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "ice",
            damageCategory = category,
            power = power,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = effects,
                scriptedBehavior = false,
            ),
        ),
    )

    private fun pokemon(
        side: BattleSide,
        speed: Int,
        hpFraction: Double,
        types: Set<String>,
        specialDefence: Int,
        heldItem: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = 0,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = hpFraction,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = heldItem,
        fainted = false,
        knownTypeIds = types,
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(150, 150),
            attack = BattleIntegerRange(110, 110),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(130, 130),
            specialDefence = BattleIntegerRange(specialDefence, specialDefence),
            speed = BattleIntegerRange(speed, speed),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )
}
