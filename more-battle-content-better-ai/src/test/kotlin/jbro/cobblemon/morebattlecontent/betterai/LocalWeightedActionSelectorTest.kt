package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCandidateFactsView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleMind
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalPositionRiskBudget
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalTrainerStyleModel
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalWeightedActionSelectorTest {
    private val selector = LocalWeightedActionSelector()

    @Test
    fun `top forty percent keeps at least two choices when alternatives exist`() {
        assertEquals(1, selector.shortlistSize(1))
        assertEquals(2, selector.shortlistSize(2))
        assertEquals(2, selector.shortlistSize(3))
        assertEquals(2, selector.shortlistSize(4))
        assertEquals(2, selector.shortlistSize(5))
        assertEquals(3, selector.shortlistSize(6))
        assertEquals(4, selector.shortlistSize(10))
    }

    @Test
    fun `same public battle seed reproduces the same weighted choice`() {
        val ranked = listOf(rank("best", 100.0), rank("second", 90.0), rank("third", 80.0))

        val first = selector.choose(ranked, seed = 912_734L, riskTolerance = 0.5)
        val replay = selector.choose(ranked, seed = 912_734L, riskTolerance = 0.5)

        assertEquals(first.rank.outcome.candidate.actionId, replay.rank.outcome.candidate.actionId)
    }

    @Test
    fun `weighted choice favors the higher score but still selects the runner up`() {
        val ranked = listOf(rank("best", 100.0), rank("runner_up", 90.0), rank("excluded", 89.0))
        val counts = (0L until 10_000L)
            .map { selector.choose(ranked, seed = it, riskTolerance = 0.5).rank.outcome.candidate.actionId }
            .groupingBy { it }
            .eachCount()

        assertTrue(counts.getValue("best") > counts.getValue("runner_up"))
        assertTrue(counts.getValue("runner_up") > 0)
        assertEquals(null, counts["excluded"])
    }

    @Test
    fun `publicly inert action has zero weight while a useful action exists`() {
        val ranked = listOf(
            rank("immune_extreme_speed", 1_000.0, publiclyInert = true),
            rank("flare_blitz", 10.0),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "flare_blitz",
                selector.choose(ranked, seed = seed.toLong(), riskTolerance = 1.0).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `risk tolerant personality flattens the final distribution without changing the shortlist`() {
        val ranked = listOf(rank("safe", 100.0), rank("risky", 90.0), rank("excluded", 80.0))
        fun riskySelections(riskTolerance: Double): Int = (0L until 10_000L).count { seed ->
            selector.choose(ranked, seed, riskTolerance).rank.outcome.candidate.actionId == "risky"
        }

        assertTrue(riskySelections(0.9) > riskySelections(0.1))
    }

    @Test
    fun `pattern breaker requires public adaptation evidence before favoring an alternative`() {
        val ranked = listOf(
            rank("repeat", 100.0, moveId = "shadowball"),
            rank("mixup", 96.0, moveId = "willowisp"),
            rank("excluded", 80.0, moveId = "protect"),
        )
        val unconfirmed = BattleTacticalMemoryView(
            lastMoveId = "shadowball",
            sameMoveRepeatCount = 3,
            patternExposureCount = 3,
        )
        val confirmed = BattleTacticalMemoryView(
            lastMoveId = "shadowball",
            sameMoveRepeatCount = 3,
            patternExposureCount = 3,
            patternResponseShiftEvidence = 0.8,
        )
        val noRead = mixingContext(
            information = 1.0,
            planPersistence = 0.0,
            memory = unconfirmed,
            styleSeed = 77L,
        )
        val breaker = mixingContext(
            information = 1.0,
            planPersistence = 0.0,
            memory = confirmed,
            styleSeed = 77L,
        )
        val persistent = mixingContext(
            information = 0.0,
            planPersistence = 1.0,
            memory = confirmed,
            styleSeed = 77L,
        )

        fun alternatives(context: LocalActionMixingContext): Int = (0L until 10_000L).count { seed ->
            selector.choose(ranked, seed, context).rank.outcome.candidate.actionId == "mixup"
        }

        assertTrue(alternatives(breaker) > alternatives(noRead))
        assertTrue(alternatives(breaker) > alternatives(persistent))
    }

    @Test
    fun `being behind raises credible risk taking while being ahead lowers it`() {
        val ranked = listOf(
            rank("safe", 100.0, moveId = "surf", accuracy = 1.0),
            rank("risky", 94.0, moveId = "hydropump", accuracy = 0.8),
        )
        val ahead = mixingContext(riskTolerance = 0.5, positionAdvantage = 0.8)
        val behind = mixingContext(riskTolerance = 0.5, positionAdvantage = -0.8)
        fun risky(context: LocalActionMixingContext): Int = (0L until 10_000L).count { seed ->
            selector.choose(ranked, seed, context).rank.outcome.candidate.actionId == "risky"
        }

        assertTrue(risky(behind) > risky(ahead))
    }

    @Test
    fun `safe entry intent cannot reward another immediate switch`() {
        val switch = rank("switch_again", 95.0).outcome.candidate.copyForTest(BattleActionKind.SWITCH)
        val memory = BattleTacticalMemoryView(
            activePlan = BattlePlanView(BattlePlanIntent.CREATE_SAFE_ENTRY, expiresAtTurn = 5),
            turnsSinceLastSwitch = 1,
        )

        assertEquals(1.0, LocalBattleMind.planAlignment(switch, memory))
    }

    @Test
    fun `risk appetite favors a credible inaccurate line but cannot revive a dominated line`() {
        val ranked = listOf(
            rank("safe", 100.0, moveId = "surf", accuracy = 1.0),
            rank("risky", 94.0, moveId = "hydropump", accuracy = 0.8),
            rank("dominated", -100.0, moveId = "reckless", accuracy = 0.5),
        )
        val cautious = mixingContext(riskTolerance = 0.0, styleSeed = 19L)
        val daring = mixingContext(riskTolerance = 1.0, styleSeed = 19L)

        fun risky(context: LocalActionMixingContext): Int = (0L until 10_000L).count { seed ->
            selector.choose(ranked, seed, context).rank.outcome.candidate.actionId == "risky"
        }

        assertTrue(risky(daring) > risky(cautious))
        repeat(1_000) { seed ->
            assertTrue(selector.choose(ranked, seed.toLong(), daring).rank.outcome.candidate.actionId != "dominated")
        }
    }

    @Test
    fun `top forty percent does not rescue an absurdly inferior action`() {
        val ranked = listOf(rank("sound_action", 100.0), rank("absurd_action", -100.0))

        repeat(1_000) { seed ->
            val choice = selector.choose(ranked, seed.toLong(), riskTolerance = 1.0)
            assertEquals("sound_action", choice.rank.outcome.candidate.actionId)
            assertEquals(1, choice.shortlistSize)
        }
    }

    @Test
    fun `top forty percent still removes a strategically dominated same sign action`() {
        val ranked = listOf(
            rank("best", 100.0),
            rank("credible", 70.0),
            rank("dominated", 5.0),
            rank("outside_one", 4.0),
            rank("outside_two", 3.0),
            rank("outside_three", 2.0),
        )

        // The shortlist size is no longer asserted. Weight now decays with regret instead of falling
        // off a cliff, so a dominated action can sit in the list at negligible weight; what matters
        // is that it never gets played, which is what this checks.
        repeat(100) { seed ->
            val choice = selector.choose(ranked, seed.toLong(), riskTolerance = 1.0)
            assertTrue(choice.rank.outcome.candidate.actionId != "dominated")
        }
    }

    @Test
    fun `risk budget widens regret allowance only for credible alternatives`() {
        val ranked = listOf(
            rank("best", 100.0),
            rank("moderate", 60.0),
            rank("high_risk", 40.0),
            rank("outside_one", 20.0),
            rank("outside_two", 10.0),
            rank("outside_three", 0.0),
        )

        // Stated as behaviour rather than as a shortlist size. The size was a proxy for "how many
        // actions are live", which stopped being a clean count once weight began decaying smoothly -
        // an action can be listed and still be all but unreachable. What the name promises is that a
        // bold trainer reaches past the best action more often than a cautious one does, so that is
        // what is measured.
        val cautiousAlternatives = (0 until 200).count { seed ->
            selector.choose(ranked, seed.toLong(), riskTolerance = 0.0).rank.outcome.candidate.actionId != "best"
        }
        val boldAlternatives = (0 until 200).count { seed ->
            selector.choose(ranked, seed.toLong(), riskTolerance = 1.0).rank.outcome.candidate.actionId != "best"
        }
        assertTrue(
            boldAlternatives > cautiousAlternatives,
            "bold=$boldAlternatives cautious=$cautiousAlternatives",
        )
        // The widening is for credible alternatives only; the tail must stay unreachable at any risk.
        repeat(200) { seed ->
            val chosen = selector.choose(ranked, seed.toLong(), riskTolerance = 1.0)
                .rank.outcome.candidate.actionId
            assertTrue(chosen !in setOf("outside_one", "outside_two", "outside_three"), chosen)
        }
    }

    @Test
    fun `uncertain conditional move gets a narrower regret allowance`() {
        val ranked = listOf(
            rank("reliable", 100.0),
            rank("conditional", 51.0),
        )
        val unrestricted = mixingContext(riskTolerance = 1.0)
        val conditional = unrestricted.copy(uncertainConditionalActionIds = setOf("conditional"))

        // Narrower means chosen less often, which survives the move from a cliff to a decay; the
        // old size assertion only described where the cliff happened to sit.
        val unrestrictedPicks = (0 until 200).count { seed ->
            selector.choose(ranked, seed.toLong(), unrestricted).rank.outcome.candidate.actionId == "conditional"
        }
        val conditionalPicks = (0 until 200).count { seed ->
            selector.choose(ranked, seed.toLong(), conditional).rank.outcome.candidate.actionId == "conditional"
        }
        assertTrue(
            conditionalPicks < unrestrictedPicks,
            "conditional=$conditionalPicks unrestricted=$unrestrictedPicks",
        )
        assertEquals("reliable", selector.choose(ranked, 7L, conditional).rank.outcome.candidate.actionId)
    }

    @Test
    fun `nearby score cannot give weight to a move that will almost never execute`() {
        val ranked = listOf(
            rank("safe_switch", 100.0, executionProbability = 1.0),
            rank("doomed_attack", 99.0, executionProbability = 0.05),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "safe_switch",
                selector.choose(ranked, seed.toLong(), riskTolerance = 1.0).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `non best switch that loses most of its hp to a known response gets no exploratory weight`() {
        val ranked = listOf(
            rank("reliable_move", 100.0),
            rank(
                "nearby_suicide_switch",
                99.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.11,
            ),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "reliable_move",
                selector.choose(ranked, seed.toLong(), riskTolerance = 1.0).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `best ranked switch that loses most of its hp is rejected when an attack can execute`() {
        val ranked = listOf(
            rank(
                "best_but_bad_switch",
                110.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.33,
            ),
            rank("credible_attack", 100.0, executableDamageActions = 1),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "credible_attack",
                selector.choose(ranked, seed.toLong(), riskTolerance = 1.0).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `unsafe best switch remains available when every attack is certain to be stopped`() {
        val ranked = listOf(
            rank(
                "only_escape",
                110.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.33,
            ),
            rank(
                "faints_before_attack",
                100.0,
                executableDamageActions = 1,
                executionProbability = 0.0,
            ),
        )

        assertEquals(
            "only_escape",
            selector.choose(ranked, seed = 7L, riskTolerance = 1.0).rank.outcome.candidate.actionId,
        )
    }

    @Test
    fun `absurdly inferior attack cannot veto the best available switch`() {
        val ranked = listOf(
            rank(
                "costly_but_best_switch",
                110.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.33,
            ),
            rank("hopeless_attack", -100.0, executableDamageActions = 1),
        )

        assertEquals(
            "costly_but_best_switch",
            selector.choose(ranked, seed = 7L, riskTolerance = 1.0).rank.outcome.candidate.actionId,
        )
    }

    @Test
    fun `switch veto score gap includes exactly 199 points but not anything worse`() {
        val costlySwitch = rank(
            "costly_switch",
            110.0,
            kind = BattleActionKind.SWITCH,
            worstResponseHpRetention = 0.33,
        )
        val exactBoundary = rank("exact_boundary_attack", -89.0, executableDamageActions = 1)
        val outsideBoundary = rank("outside_boundary_attack", -89.01, executableDamageActions = 1)

        assertEquals(
            "exact_boundary_attack",
            selector.choose(listOf(costlySwitch, exactBoundary), 7L, 1.0).rank.outcome.candidate.actionId,
        )
        assertEquals(
            "costly_switch",
            selector.choose(listOf(costlySwitch, outsideBoundary), 7L, 1.0).rank.outcome.candidate.actionId,
        )
    }

    @Test
    fun `risk budget changes the exploratory switch loss limit without allowing a majority hp loss`() {
        val moderateLoss = listOf(
            rank("reliable_move", 100.0),
            rank(
                "moderate_risk_switch",
                99.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.65,
            ),
        )
        val majorityLoss = listOf(
            rank("reliable_move", 100.0),
            rank(
                "majority_loss_switch",
                99.0,
                kind = BattleActionKind.SWITCH,
                worstResponseHpRetention = 0.59,
            ),
        )
        fun switchSelections(ranked: List<LocalBattleActionRank>, riskTolerance: Double): Int =
            (0L until 10_000L).count { seed ->
                selector.choose(ranked, seed, riskTolerance).rank.outcome.candidate.kind == BattleActionKind.SWITCH
            }

        assertEquals(0, switchSelections(moderateLoss, riskTolerance = 0.0))
        assertTrue(switchSelections(moderateLoss, riskTolerance = 1.0) > 0)
        assertEquals(0, switchSelections(majorityLoss, riskTolerance = 1.0))
    }

    @Test
    fun `alternating control moves lose exploratory weight after repeated turns without progress`() {
        val ranked = listOf(
            rank("recover", 100.0),
            rank("attack", 96.0, moveId = "surf", executableDamageActions = 1),
        )
        val fresh = mixingContext()
        val stalled = mixingContext(memory = BattleTacticalMemoryView(nonProgressControlStreak = 2))
        fun attackSelections(context: LocalActionMixingContext): Int = (0L until 10_000L).count { seed ->
            selector.choose(ranked, seed, context).rank.outcome.candidate.actionId == "attack"
        }

        assertTrue(attackSelections(stalled) > attackSelections(fresh))
    }

    @Test
    fun `self setup that is answered by a known knockout gets no weight when damage can execute`() {
        val ranked = listOf(
            rank(
                "doomed_nasty_plot",
                110.0,
                moveId = "nastyplot",
                selfSetup = true,
                worstResponseHpRetention = 0.0,
            ),
            rank("focus_blast", 100.0, moveId = "focusblast", executableDamageActions = 1),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "focus_blast",
                selector.choose(ranked, seed.toLong(), mixingContext()).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `known knockout rejects best setup even when its score gap made the attack look noncredible`() {
        val ranked = listOf(
            rank(
                "doomed_best_nasty_plot",
                310.0,
                moveId = "nastyplot",
                selfSetup = true,
                worstResponseHpRetention = 0.0,
            ),
            rank("dark_pulse", 100.0, moveId = "darkpulse", executableDamageActions = 1),
        )

        repeat(1_000) { seed ->
            assertEquals(
                "dark_pulse",
                selector.choose(ranked, seed.toLong(), mixingContext()).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `two setup turns remain possible but a third identical setup gets no weight`() {
        val ranked = listOf(
            rank("nasty_plot", 110.0, moveId = "nastyplot", selfSetup = true),
            rank("focus_blast", 100.0, moveId = "focusblast", executableDamageActions = 1),
        )
        fun setupSelections(repeats: Int): Int {
            val memory = BattleTacticalMemoryView(lastMoveId = "nastyplot", sameMoveRepeatCount = repeats)
            return (0L until 1_000L).count { seed ->
                selector.choose(ranked, seed, mixingContext(memory = memory))
                    .rank.outcome.candidate.actionId == "nasty_plot"
            }
        }

        assertTrue(setupSelections(repeats = 1) > 0, "A second setup turn must remain available")
        assertEquals(0, setupSelections(repeats = 2), "A third identical setup turn must be blocked")
    }

    @Test
    fun `an already boosted non best setup gets no exploratory weight when damage can execute`() {
        val ranked = listOf(
            rank("credible_attack", 110.0, moveId = "shadowball", executableDamageActions = 1),
            rank("alternate_setup", 105.0, moveId = "agility", selfSetup = true),
        )
        val context = mixingContext().copy(alreadyBoostedSetupActionIds = setOf("alternate_setup"))

        repeat(1_000) { seed ->
            val choice = selector.choose(ranked, seed.toLong(), context)
            assertEquals("credible_attack", choice.rank.outcome.candidate.actionId)
            assertEquals(1, choice.shortlistSize)
        }
    }

    @Test
    fun `an already boosted setup remains available when lookahead still ranks it best`() {
        val ranked = listOf(
            rank("threshold_setup", 110.0, moveId = "dragondance", selfSetup = true),
            rank("credible_attack", 105.0, moveId = "dragonclaw", executableDamageActions = 1),
        )
        val context = mixingContext().copy(alreadyBoostedSetupActionIds = setOf("threshold_setup"))

        assertTrue((0L until 1_000L).any { seed ->
            selector.choose(ranked, seed, context).rank.outcome.candidate.actionId == "threshold_setup"
        })
    }

    @Test
    fun `an overcommitted setup is rejected even when lookahead ranks it best`() {
        val ranked = listOf(
            rank("overcommitted_setup", 110.0, moveId = "quiverdance", selfSetup = true),
            rank("credible_attack", 100.0, moveId = "bugbuzz", executableDamageActions = 1),
        )
        val context = mixingContext().copy(overcommittedSetupActionIds = setOf("overcommitted_setup"))

        repeat(1_000) { seed ->
            assertEquals(
                "credible_attack",
                selector.choose(ranked, seed.toLong(), context).rank.outcome.candidate.actionId,
            )
        }
    }

    @Test
    fun `an overcommitted setup yields to an attack attempt even when neither line is likely to execute`() {
        val ranked = listOf(
            rank("only_progress", 110.0, moveId = "quiverdance", selfSetup = true),
            rank("doomed_attack", 100.0, moveId = "bugbuzz", executableDamageActions = 1, executionProbability = 0.0),
        )
        val context = mixingContext().copy(overcommittedSetupActionIds = setOf("only_progress"))

        assertEquals("doomed_attack", selector.choose(ranked, 7L, context).rank.outcome.candidate.actionId)
    }

    private fun rank(
        actionId: String,
        score: Double,
        publiclyInert: Boolean = false,
        moveId: String? = null,
        accuracy: Double? = null,
        executableDamageActions: Int = 0,
        executionProbability: Double = 1.0,
        kind: BattleActionKind = BattleActionKind.USE_MOVE,
        worstResponseHpRetention: Double = 1.0,
        selfSetup: Boolean = false,
    ): LocalBattleActionRank {
        val candidate = BattleActionCandidate(
            actionId = actionId,
            kind = kind,
            actorSlot = 0,
            moveSlot = if (kind == BattleActionKind.USE_MOVE) 0 else null,
            moveId = if (kind == BattleActionKind.USE_MOVE) moveId else null,
            moveDetails = if (selfSetup) {
                jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView(
                    typeId = "dark",
                    damageCategory = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory.STATUS,
                    power = 0.0,
                    accuracy = 100.0,
                    priority = 0,
                    currentPp = 10,
                    targetPattern = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern.SELF,
                    effects = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView(
                        coverage = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        effects = listOf(
                            jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView(
                                kind = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind.STAT_STAGE,
                                target = jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget.USER,
                                probability = 1.0,
                                statStages = mapOf("special_attack" to 2),
                            ),
                        ),
                        scriptedBehavior = false,
                    ),
                )
            } else {
                null
            },
            switchPokemonId = if (kind == BattleActionKind.SWITCH) {
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000401")
            } else {
                null
            },
            facts = accuracy?.let { BattleCandidateFactsView(baseAccuracyProbability = it) },
        )
        return LocalBattleActionRank(
            outcome = LocalBattleActionOutcome(
                candidate = candidate,
                tacticalUtility = score,
                expectedDamageFraction = 0.0,
                secureStandardKnockouts = 0,
                executableDamageActions = executableDamageActions,
                publiclyInert = publiclyInert,
                entryFaints = false,
                switchPostEntryHp = null,
                currentDefensiveExposure = null,
                resultingDefensiveExposure = null,
                survivalPositionImprovement = null,
            ),
            decisionTier = 3,
            comparisonValue = score,
            executionProbability = executionProbability,
            worstResponseHpRetention = worstResponseHpRetention,
        )
    }

    private fun BattleActionCandidate.copyForTest(kind: BattleActionKind): BattleActionCandidate = when (kind) {
        BattleActionKind.SWITCH -> BattleActionCandidate(
            actionId = actionId,
            kind = kind,
            actorSlot = 0,
            switchPokemonId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000401"),
        )
        else -> this
    }


    private fun mixingContext(
        riskTolerance: Double = 0.5,
        information: Double = 0.5,
        planPersistence: Double = 0.5,
        memory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
        styleSeed: Long = 1L,
        positionAdvantage: Double = 0.0,
    ) = LocalActionMixingContext(
        personality = BattleTrainerPersonality(
            aggression = 0.5,
            caution = 0.5,
            switching = 0.5,
            information = information,
            planPersistence = planPersistence,
            riskTolerance = riskTolerance,
        ),
        memory = memory,
        style = LocalTrainerStyleModel.fromSeed(styleSeed),
        riskBudget = LocalPositionRiskBudget.resolve(riskTolerance, positionAdvantage, 0.0),
    )
}
