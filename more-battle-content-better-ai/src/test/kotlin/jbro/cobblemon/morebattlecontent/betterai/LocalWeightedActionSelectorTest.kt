package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCandidateFactsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
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
    ): LocalBattleActionRank {
        val candidate = BattleActionCandidate(
            actionId = actionId,
            kind = kind,
            actorSlot = 0,
            moveSlot = if (kind == BattleActionKind.USE_MOVE) 0 else null,
            moveId = if (kind == BattleActionKind.USE_MOVE) moveId else null,
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
