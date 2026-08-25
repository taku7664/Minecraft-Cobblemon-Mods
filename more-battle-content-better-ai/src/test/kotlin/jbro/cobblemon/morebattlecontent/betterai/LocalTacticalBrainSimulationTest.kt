package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleCalculationCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleCandidateFactsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDamageFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnowledgePolicy
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanUpdateOperation
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStandardDamageModel
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamMemberPlan
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalHighestRankedActionSelector
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTacticalBrainSimulationTest {
    private val brain = LocalTacticalBrain(LocalHighestRankedActionSelector)
    private val session = brain.openSession(
        BattleBrainOpenContext(UUID.randomUUID(), BattleFormat.SINGLE, BattleKnowledgePolicy.FAIR_INFERENCE),
    )

    @Test
    fun `single legal action skips tactical ranking and recursive search`() {
        val decision = decide(listOf(move("only", power = 40.0)))

        assertEquals("only", decision.actionId)
        assertEquals(1.0, decision.confidence)
        assertTrue("single_legal_action" in decision.tags)
        assertTrue(decision.tags.none { it.startsWith("lookahead_nodes_") })
    }

    @Test
    fun `reliable high pressure move beats weaker or inaccurate alternatives`() {
        val candidates = listOf(
            move("weak", power = 50.0, accuracy = 100.0),
            move("strong", power = 90.0, accuracy = 100.0),
            move("risky", power = 120.0, accuracy = 50.0),
        )

        assertEquals("strong", decide(candidates).actionId)
    }

    @Test
    fun `critical active hp makes a legal switch preferable to mediocre pressure`() {
        val switch = BattleActionCandidate(
            actionId = "switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = UUID.randomUUID(),
        )

        assertEquals("switch", decide(listOf(move("chip", 35.0), switch), allyHp = 0.12).actionId)
    }

    @Test
    fun `public knockout threat can justify switching before the active reaches critical hp`() {
        val safeId = UUID.randomUUID()

        assertEquals(
            "switch_safe",
            decide(
                candidates = listOf(move("chip", 60.0, typeId = "grass"), switch("switch_safe", safeId)),
                allyHp = 0.8,
                allyTypes = setOf("grass"),
                opponentTypes = setOf("fire"),
                allyBench = mapOf(safeId to BenchPokemon(0.8, setOf("water"))),
            ).actionId,
        )
    }

    @Test
    fun `switch target uses public matchup instead of action id tie breaking`() {
        val unsafeId = UUID.randomUUID()
        val safeId = UUID.randomUUID()

        assertEquals(
            "z_safe",
            decide(
                candidates = listOf(switch("a_unsafe", unsafeId), switch("z_safe", safeId)),
                allyHp = 0.1,
                allyTypes = setOf("grass"),
                opponentTypes = setOf("fire"),
                allyBench = mapOf(
                    unsafeId to BenchPokemon(0.9, setOf("grass")),
                    safeId to BenchPokemon(0.9, setOf("water")),
                ),
            ).actionId,
        )
    }

    @Test
    fun `recent switch is discouraged when staying is not a public knockout risk`() {
        val benchId = UUID.randomUUID()

        assertEquals(
            "steady",
            decide(
                candidates = listOf(move("steady", 35.0), switch("switch_again", benchId)),
                allyHp = 0.7,
                allyBench = mapOf(benchId to BenchPokemon(1.0)),
                memory = BattleTacticalMemoryView(turnsSinceLastSwitch = 0, switchesThisBattle = 1),
            ).actionId,
        )
    }

    @Test
    fun `healthy neutral position attacks instead of spending the turn on a lateral switch`() {
        val benchId = UUID.randomUUID()

        assertEquals(
            "attack",
            decide(
                candidates = listOf(
                    move("attack", 40.0, facts = damageFacts(0.2)),
                    switch("switch", benchId),
                ),
                allyHp = 0.9,
                allyTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("normal"))),
            ).actionId,
        )
    }

    @Test
    fun `pivoting objective does not reward a lateral switch without public positioning gain`() {
        val benchId = UUID.randomUUID()
        val pivotingSession = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(4),
                strategy = BattleStrategyBrief(
                    strategyId = "mbc:pivoting",
                    displayNameKey = "strategy.mbc.pivoting.name",
                    descriptionKey = "strategy.mbc.pivoting.description",
                    aiSummary = "Pivot only when it improves the public position.",
                    objectives = setOf(BattleStrategyObjective.PIVOTING),
                ),
            ),
        )

        assertEquals(
            "attack",
            decide(
                candidates = listOf(
                    move("attack", 40.0, facts = damageFacts(0.2)),
                    switch("switch", benchId),
                ),
                allyHp = 1.0,
                allyTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("normal"))),
                selectedSession = pivotingSession,
            ).actionId,
        )
    }

    @Test
    fun `recent switch pressure stops a marginal cycle but old switches do not stay as permanent debt`() {
        val benchId = UUID.randomUUID()
        val candidates = listOf(
            move("attack", 80.0, facts = damageFacts(0.4)),
            switch("switch", benchId),
        )
        val bench = mapOf(benchId to BenchPokemon(1.0, setOf("normal")))

        // A full-HP attacker with a 40% attack available no longer rotates out just because the
        // incoming matchup is neutral instead of unfavourable. Sweeping the switch exposure weight
        // from 50 to 300 (see LocalSwitchWeightSweepTest) moves win share by less than sampling noise
        // while switches per battle climb from 1.8 to 3.0 and the stall rate rises from 6.7% to 10%,
        // so the extra rotation buys nothing and costs decisiveness.
        assertEquals(
            "attack",
            decide(
                candidates = candidates,
                allyHp = 1.0,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("water"),
                allyBench = bench,
            ).actionId,
        )
        assertEquals(
            "attack",
            decide(
                candidates = candidates,
                allyHp = 1.0,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("water"),
                allyBench = bench,
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 1,
                    switchPressure = 1.0,
                ),
            ).actionId,
        )
        // Old switches are still not permanent debt - the tier gate reopens, which is what the two
        // immunity tests above verify. What changed is that reopening the gate is no longer enough on
        // its own: a lateral swap into a merely neutral matchup scores about 24 against 40 for the
        // attack, so the attack wins on merit rather than on switch fatigue. The fatigue decay is
        // tested where it is actually decisive, not where the scores are far apart.
        assertEquals(
            "attack",
            decide(
                candidates = candidates,
                allyHp = 1.0,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("water"),
                allyBench = bench,
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 10,
                    switchesThisBattle = 5,
                    switchPressure = 0.0,
                ),
            ).actionId,
        )
    }

    @Test
    fun `switch fatigue never blocks escape from an overwhelming public matchup`() {
        val benchId = UUID.randomUUID()

        assertEquals(
            "switch",
            decide(
                candidates = listOf(
                    move("attack", 80.0, facts = damageFacts(0.4)),
                    switch("switch", benchId),
                ),
                allyHp = 0.8,
                allyTypes = setOf("fire", "flying"),
                opponentTypes = setOf("rock"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("fighting"))),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 5,
                    switchPressure = 4.0,
                ),
            ).actionId,
        )
    }

    @Test
    fun `double switch evaluates the acting slot instead of borrowing partner danger`() {
        val benchId = UUID.randomUUID()

        assertEquals(
            "attack",
            decide(
                candidates = listOf(
                    move("attack", 40.0, actorSlot = 1, facts = damageFacts(0.2)),
                    switch("switch", benchId, actorSlot = 1),
                ),
                allyHp = 0.1,
                allyTypes = setOf("grass"),
                opponentTypes = setOf("fire"),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = setOf("water"),
                allyPartnerHp = 1.0,
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("normal"))),
            ).actionId,
        )
    }

    @Test
    fun `double composite cannot bypass repeated switch outcome policy`() {
        val benchId = UUID.randomUUID()
        val attackBoth = composite(
            "attack_both",
            move("slot_zero_attack", 60.0, actorSlot = 0, targetSlot = 0, facts = damageFacts(0.20)),
            move("slot_one_attack", 80.0, actorSlot = 1, targetSlot = 0, facts = damageFacts(0.40)),
        )
        val switchAgain = composite(
            "switch_again",
            switch("slot_zero_switch", benchId, actorSlot = 0),
            move("slot_one_same_attack", 80.0, actorSlot = 1, targetSlot = 0, facts = damageFacts(0.40)),
        )

        assertEquals(
            "attack_both",
            decide(
                candidates = listOf(attackBoth, switchAgain),
                format = BattleFormat.DOUBLE,
                allyHp = 0.10,
                allyTypes = setOf("normal"),
                allyPartnerTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("normal"))),
                memory = BattleTacticalMemoryView(turnsSinceLastSwitch = 1, switchesThisBattle = 2),
            ).actionId,
        )
    }

    @Test
    fun `a visible repeated move pattern can be broken without sacrificing a large advantage`() {
        val memory = BattleTacticalMemoryView(
            lastMoveId = "cobblemon:repeat",
            sameMoveRepeatCount = 2,
            patternExposureCount = 2,
            patternResponseShiftEvidence = 0.8,
        )

        assertEquals(
            "alternative",
            decide(listOf(move("repeat", 80.0), move("alternative", 75.0)), memory = memory).actionId,
        )
        assertEquals(
            "repeat",
            decide(listOf(move("repeat", 110.0), move("alternative", 75.0)), memory = memory).actionId,
        )
    }

    @Test
    fun `server facts can make recovery better than a low hp switch`() {
        val benchId = UUID.randomUUID()
        val recovery = move(
            id = "recover",
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.75, 0.85),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )

        assertEquals(
            "recover",
            decide(
                candidates = listOf(recovery, switch("switch", benchId)),
                allyHp = 0.15,
                allyBench = mapOf(benchId to BenchPokemon(0.4)),
            ).actionId,
        )
    }

    @Test
    fun `full hp does not waste the turn on a declared pure recovery move`() {
        val recovery = move(
            id = "recover_at_full_hp",
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.HEAL_FRACTION,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        fractionRange = BattleFractionRange(0.5, 0.5),
                    ),
                ),
                scriptedBehavior = true,
            ),
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.5, 0.5),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )
        val chip = move("small_damage", power = 30.0, facts = damageFacts(0.10))

        assertEquals("small_damage", decide(listOf(recovery, chip), allyHp = 1.0).actionId)
    }

    @Test
    fun `recovery loop stops when the public hp loss erased the previous heal`() {
        val actorId = UUID.randomUUID()
        val recovery = move(
            id = "recover",
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.HEAL_FRACTION,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        fractionRange = BattleFractionRange(0.5, 0.5),
                    ),
                ),
                scriptedBehavior = true,
            ),
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.5, 0.5),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))
        val events = listOf(
            BattleObservedEventView(
                sequence = 10,
                turn = 4,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = actorId,
                publicValueId = "cobblemon:recover",
            ),
            BattleObservedEventView(
                sequence = 11,
                turn = 4,
                kind = BattleObservedEventKind.HP_CHANGED,
                actorPokemonId = actorId,
                hpFractionDelta = 0.5,
            ),
            BattleObservedEventView(
                sequence = 12,
                turn = 4,
                kind = BattleObservedEventKind.HP_CHANGED,
                actorPokemonId = actorId,
                hpFractionDelta = -0.55,
            ),
        )

        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(recovery, chip),
                turn = 5,
                allyPokemonId = actorId,
                allyHp = 0.10,
                observedEvents = events,
                memory = BattleTacticalMemoryView(lastMoveId = "cobblemon:recover", sameMoveRepeatCount = 1),
            ).actionId,
        )
    }

    @Test
    fun `repeated pure recovery yields at high hp but remains available for low hp survival`() {
        val recovery = move(
            id = "recover",
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.HEAL_FRACTION,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        fractionRange = BattleFractionRange(0.5, 0.5),
                    ),
                ),
                scriptedBehavior = true,
            ),
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.5, 0.5),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))
        val repeatedRecovery = BattleTacticalMemoryView(
            lastMoveId = "cobblemon:recover",
            sameMoveRepeatCount = 3,
        )

        assertEquals("recover", decide(listOf(recovery, chip), allyHp = 0.82).actionId)
        assertEquals(
            "small_damage",
            decide(listOf(recovery, chip), allyHp = 0.82, memory = repeatedRecovery).actionId,
        )
        assertEquals(
            "recover",
            decide(listOf(recovery, chip), allyHp = 0.25, memory = repeatedRecovery).actionId,
        )
    }

    @Test
    fun `all three tower mechanics are judged from facts instead of calculator recommendations`() {
        val base = move("base", 90.0, facts = damageFacts(0.5))
        listOf("mega", "dynamax", "tera").forEach { mechanicId ->
            val wasteful = move(
                mechanicId,
                90.0,
                mechanic = mechanicId,
                facts = damageFacts(0.6),
            )
            val reversal = move(
                "${mechanicId}_reversal",
                90.0,
                mechanic = mechanicId,
                facts = damageFacts(0.9),
            )

            assertEquals(
                "base",
                decide(listOf(base, wasteful), turn = 6, allyHp = 0.6, opponentHp = 0.45).actionId,
                mechanicId,
            )
            assertEquals(
                "${mechanicId}_reversal",
                decide(listOf(base, reversal), turn = 1, allyHp = 1.0, opponentHp = 1.0).actionId,
                mechanicId,
            )
        }
    }

    @Test
    fun `active safe entry plan favors the public team member with the target role`() {
        val pivotId = UUID.randomUUID()
        val aceId = UUID.randomUUID()
        val strategySession = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(4),
                strategy = BattleStrategyBrief(
                    strategyId = "mbc:safe_entry",
                    displayNameKey = "strategy.mbc.safe_entry.name",
                    descriptionKey = "strategy.mbc.safe_entry.description",
                    aiSummary = "Create a safe entry for the ace.",
                    objectives = setOf(BattleStrategyObjective.PIVOTING),
                    members = listOf(
                        BattleTeamMemberPlan("showdown:pivot", setOf(BattleTeamRole.PIVOT), "Absorb pressure."),
                        BattleTeamMemberPlan("showdown:ace", setOf(BattleTeamRole.ACE), "Close the game."),
                    ),
                ),
            ),
        )

        assertEquals(
            "z_ace",
            decide(
                candidates = listOf(switch("a_pivot", pivotId), switch("z_ace", aceId)),
                allyHp = 0.15,
                allyBench = mapOf(
                    pivotId to BenchPokemon(0.9, speciesId = "showdown:pivot"),
                    aceId to BenchPokemon(0.9, speciesId = "showdown:ace"),
                ),
                memory = BattleTacticalMemoryView(
                    activePlan = BattlePlanView(BattlePlanIntent.CREATE_SAFE_ENTRY, BattleTeamRole.ACE, expiresAtTurn = 5),
                ),
                selectedSession = strategySession,
            ).actionId,
        )
    }

    @Test
    fun `local plan is kept only while the selected action still serves its intent`() {
        val advancedSession = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(4),
                trainerPersonaId = "mbc:test_planner",
            ),
        )
        val pressure = move("pressure", 90.0)
        val kept = decide(
            listOf(pressure),
            memory = BattleTacticalMemoryView(
                activePlan = BattlePlanView(BattlePlanIntent.APPLY_PRESSURE, expiresAtTurn = 6),
            ),
            selectedSession = advancedSession,
        )
        val replaced = decide(
            listOf(pressure),
            memory = BattleTacticalMemoryView(
                activePlan = BattlePlanView(BattlePlanIntent.ESTABLISH_FIELD, expiresAtTurn = 6),
            ),
            selectedSession = advancedSession,
        )

        assertEquals(BattlePlanUpdateOperation.KEEP, kept.advice?.planUpdate?.operation)
        assertEquals(BattlePlanUpdateOperation.CLEAR, replaced.advice?.planUpdate?.operation)
    }

    @Test
    fun `known opponent typing changes move pressure without reading hidden types`() {
        val normal = move("normal", power = 90.0, typeId = "normal")
        val fire = move("fire", power = 70.0, typeId = "fire")

        assertEquals("fire", decide(listOf(normal, fire), opponentTypes = setOf("grass")).actionId)
        assertEquals("normal", decide(listOf(normal, fire), opponentTypes = setOf("water")).actionId)
        assertEquals("normal", decide(listOf(normal, fire), opponentTypes = emptySet()).actionId)
    }

    @Test
    fun `ghost immunity beats extreme speed priority even at critical hp`() {
        val extremeSpeed = move("extreme_speed", power = 80.0, typeId = "normal", priority = 2)
        val ember = move("ember", power = 30.0, typeId = "fire")

        assertEquals(
            "ember",
            decide(
                candidates = listOf(extremeSpeed, ember),
                opponentHp = 0.15,
                opponentTypes = setOf("ghost"),
            ).actionId,
        )
    }

    @Test
    fun `publicly revealed levitate makes ground damage lose to a viable attack`() {
        val ground = move("ground", power = 100.0, typeId = "ground", facts = damageFacts(0.60))
        val ice = move("ice", power = 70.0, typeId = "ice", facts = damageFacts(0.25))

        assertEquals(
            "ice",
            decide(
                candidates = listOf(ground, ice),
                opponentTypes = setOf("electric"),
                opponentAbilityId = "cobblemon:levitate",
            ).actionId,
        )
    }

    @Test
    fun `sole public levitate possibility prevents ground damage before it activates`() {
        val opponentId = UUID.randomUUID()
        val ground = move("ground", power = 100.0, typeId = "ground", facts = damageFacts(0.60))
        val ice = move("ice", power = 70.0, typeId = "ice", facts = damageFacts(0.25))

        assertEquals(
            "ice",
            decide(
                candidates = listOf(ground, ice),
                opponentPokemonId = opponentId,
                opponentTypes = setOf("electric"),
                inferences = listOf(
                    BattleInferenceView(
                        subjectPokemonId = opponentId,
                        categoryId = "ability",
                        candidateId = "levitate",
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                    ),
                ),
            ).actionId,
        )
    }

    @Test
    fun `publicly revealed mold breaker bypasses a revealed levitate immunity`() {
        val ground = move("ground", power = 100.0, typeId = "ground", facts = damageFacts(0.60))
        val ice = move("ice", power = 70.0, typeId = "ice", facts = damageFacts(0.25))

        assertEquals(
            "ground",
            decide(
                candidates = listOf(ground, ice),
                allyAbilityId = "cobblemon:mold_breaker",
                opponentTypes = setOf("electric"),
                opponentAbilityId = "cobblemon:levitate",
            ).actionId,
        )
    }

    @Test
    fun `public reflect changes the local outcome comparison without rewriting standard damage facts`() {
        val physical = move(
            "physical",
            power = 100.0,
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            facts = damageFacts(0.50),
        )
        val special = move(
            "special",
            power = 80.0,
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            facts = damageFacts(0.35),
        )

        assertEquals(
            "special",
            decide(
                candidates = listOf(physical, special),
                field = sideConditionField(BattleSide.OPPONENT, "reflect", remainingTurns = 3),
            ).actionId,
        )
    }

    @Test
    fun `screen breaking damage is not reduced by the screen it removes`() {
        val brickBreak = move(
            "brick_break",
            power = 75.0,
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            facts = damageFacts(0.50),
        )
        val special = move(
            "special",
            power = 80.0,
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            facts = damageFacts(0.35),
        )

        assertEquals(
            "brick_break",
            decide(
                candidates = listOf(brickBreak, special),
                field = sideConditionField(BattleSide.OPPONENT, "reflect", remainingTurns = 3),
            ).actionId,
        )
    }

    @Test
    fun `public rain changes local fire and water outcome comparison`() {
        val fire = move("fire", power = 80.0, typeId = "fire", facts = damageFacts(0.30))
        val water = move("water", power = 80.0, typeId = "water", facts = damageFacts(0.30))

        assertEquals(
            "water",
            decide(
                candidates = listOf(fire, water),
                field = field(weatherId = "rain"),
            ).actionId,
        )
    }

    @Test
    fun `public harsh sunlight makes water damage unusable`() {
        val water = move("water", power = 120.0, typeId = "water", facts = damageFacts(0.90))
        val normal = move("normal", power = 70.0, typeId = "normal", facts = damageFacts(0.40))

        assertEquals(
            "normal",
            decide(
                candidates = listOf(water, normal),
                field = field(weatherId = "desolate_land"),
            ).actionId,
        )
    }

    @Test
    fun `dual type effectiveness is multiplied from public target types`() {
        val normal = move("normal", power = 100.0, typeId = "normal")
        val ice = move("ice", power = 40.0, typeId = "ice")

        assertEquals(
            "ice",
            decide(listOf(normal, ice), opponentTypes = setOf("grass", "flying")).actionId,
        )
    }

    @Test
    fun `same type attack bonus uses only the public active ally typing`() {
        val offType = move("off_type", power = 100.0, typeId = "normal")
        val sameType = move("same_type", power = 70.0, typeId = "fire")

        assertEquals(
            "same_type",
            decide(listOf(offType, sameType), allyTypes = setOf("fire"), opponentTypes = setOf("normal")).actionId,
        )
        assertEquals(
            "off_type",
            decide(listOf(offType, sameType), allyTypes = emptySet(), opponentTypes = setOf("normal")).actionId,
        )
    }

    @Test
    fun `critical hp prefers the healthiest public legal switch candidate`() {
        val injuredId = UUID.randomUUID()
        val healthyId = UUID.randomUUID()
        val injured = switch("injured", injuredId)
        val healthy = switch("healthy", healthyId)

        assertEquals(
            "healthy",
            decide(
                listOf(move("chip", 60.0), injured, healthy),
                allyHp = 0.1,
                allyBench = mapOf(injuredId to BenchPokemon(0.25), healthyId to BenchPokemon(0.9)),
            ).actionId,
        )
    }

    @Test
    fun `double battle spread damage accounts for publicly known ally immunity`() {
        val safe = move(
            "safe",
            power = 70.0,
            typeId = "normal",
            targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS,
        )
        val earthquake = move(
            "earthquake",
            power = 100.0,
            typeId = "ground",
            targetPattern = BattleMoveTargetPattern.ALL_ADJACENT,
        )

        assertEquals(
            "safe",
            decide(
                listOf(safe, earthquake),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
            ).actionId,
        )
        assertEquals(
            "earthquake",
            decide(
                listOf(safe, earthquake),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = setOf("flying"),
                opponentTypes = setOf("normal"),
            ).actionId,
        )
    }

    @Test
    fun `double spread pressure sums two opponents with the canonical spread modifier`() {
        val singleTarget = move("single", power = 100.0)
        val spread = move(
            "spread",
            power = 80.0,
            targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS,
        )

        assertEquals(
            "spread",
            decide(
                listOf(singleTarget, spread),
                format = BattleFormat.DOUBLE,
                opponentPartnerTypes = setOf("normal"),
            ).actionId,
        )
        assertEquals(
            "single",
            decide(listOf(singleTarget, spread), format = BattleFormat.DOUBLE).actionId,
        )
    }

    @Test
    fun `priority is reserved for a public low hp finishing window`() {
        val steady = move("steady", power = 70.0, priority = 0)
        val priority = move("priority", power = 50.0, priority = 1)

        assertEquals("steady", decide(listOf(steady, priority), opponentHp = 1.0).actionId)
        assertEquals("priority", decide(listOf(steady, priority), opponentHp = 0.1).actionId)
    }

    @Test
    fun `factory strategy brief changes the decision through structured preferred moves`() {
        val strategySession = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(4),
                strategy = BattleStrategyBrief(
                    strategyId = "mbc:rain_control",
                    displayNameKey = "strategy.mbc.rain_control.name",
                    descriptionKey = "strategy.mbc.rain_control.description",
                    aiSummary = "Establish rain before applying pressure.",
                    objectives = setOf(BattleStrategyObjective.FIELD_CONTROL),
                    members = listOf(
                        BattleTeamMemberPlan(
                            speciesId = "showdown:test",
                            roles = setOf(BattleTeamRole.SETUP_ENABLER),
                            tacticalSummary = "Use Rain Dance to create the ace's attacking window.",
                            preferredMoveIds = setOf("cobblemon:rain_dance"),
                            leadPriority = 100,
                            preservationPriority = 30,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "rain_dance",
            decide(
                listOf(move("rain_dance", 55.0), move("raw_damage", 80.0)),
                selectedSession = strategySession,
            ).actionId,
        )
        assertEquals("raw_damage", decide(listOf(move("rain_dance", 55.0), move("raw_damage", 80.0))).actionId)
    }

    @Test
    fun `introductory ignores team strategy while standard uses immediate strategy`() {
        val strategy = BattleStrategyBrief(
            strategyId = "mbc:rain_control",
            displayNameKey = "strategy.mbc.rain_control.name",
            descriptionKey = "strategy.mbc.rain_control.description",
            aiSummary = "Establish rain before applying pressure.",
            objectives = setOf(BattleStrategyObjective.FIELD_CONTROL),
            members = listOf(
                BattleTeamMemberPlan(
                    speciesId = "showdown:test",
                    roles = setOf(BattleTeamRole.SETUP_ENABLER),
                    tacticalSummary = "Use Rain Dance to create the ace's attacking window.",
                    preferredMoveIds = setOf("cobblemon:rain_dance"),
                ),
            ),
        )
        fun session(skillLevel: Int) = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                strategy = strategy,
                trainerProfile = BattleTrainerProfile.balanced(skillLevel),
            ),
        )
        val candidates = listOf(move("rain_dance", 55.0), move("raw_damage", 80.0))

        assertEquals("raw_damage", decide(candidates, selectedSession = session(1)).actionId)
        assertEquals("rain_dance", decide(candidates, selectedSession = session(2)).actionId)
    }

    @Test
    fun `factory strategy objectives influence only applicable legal candidates`() {
        val strategySession = brain.openSession(
            BattleBrainOpenContext(
                UUID.randomUUID(),
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(4),
                strategy = BattleStrategyBrief(
                    strategyId = "mbc:status_pressure",
                    displayNameKey = "strategy.mbc.status_pressure.name",
                    descriptionKey = "strategy.mbc.status_pressure.description",
                    aiSummary = "Apply status before converting the advantage into damage.",
                    objectives = setOf(BattleStrategyObjective.STATUS_PRESSURE),
                ),
            ),
        )
        val candidates = listOf(
            statusMove("status"),
            move("damage", power = 40.0),
        )
        val recovery = move(
            id = "recover",
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            effects = effects(
                BattleMoveEffectView(
                    BattleMoveEffectKind.HEAL_FRACTION,
                    BattleMoveEffectTarget.USER,
                    fractionRange = BattleFractionRange(0.5, 0.5),
                ),
            ),
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.5, 0.5),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
        )

        assertEquals("status", decide(candidates, selectedSession = strategySession).actionId)
        // Without the strategy the objective bonus is gone, but a guaranteed major status is still
        // worth more than this attack. `damage` carries no facts, so it is priced through the
        // unprojected path: 40 base power is about 13% of a health bar at level 50, against 0.35 for
        // landing paralysis. The legacy fallback read the same move as 40% of a bar - it treated base
        // power as if it were a percentage - which is what made every unprojectable attack look like
        // the best move on the board.
        assertEquals("status", decide(candidates).actionId)
        // At 90% HP a 50% recovery restores only the 10% that is missing, which the evaluator already
        // caps it to. That leaves 0.10 of a bar healed against 25 base power - about 0.083 of a bar -
        // so recovering really is the larger number here. The guard being tested is the cap, and the
        // cap is doing its job; the winner is decided by arithmetic that is now on one scale.
        assertEquals(
            "recover",
            decide(
                listOf(recovery, move("low_damage", power = 25.0)),
                allyHp = 0.90,
                selectedSession = strategySession,
            ).actionId,
        )
    }

    @Test
    fun `ten thousand generated decisions attack with the highest public pressure instead of switching laterally`() {
        val random = Random(731_993)
        val benchId = UUID.randomUUID()
        repeat(10_000) { iteration ->
            val moves = (0 until random.nextInt(2, 7)).map { index ->
                move(
                    id = "m$index",
                    power = random.nextInt(20, 151).toDouble(),
                    accuracy = random.nextInt(50, 101).toDouble(),
                )
            }
            val lateralSwitch = switch("switch", benchId)
            val forfeit = BattleActionCandidate("forfeit", BattleActionKind.FORFEIT)
            val decision = decide(
                candidates = moves + lateralSwitch + forfeit,
                allyHp = 1.0,
                allyTypes = setOf("normal"),
                opponentTypes = setOf("normal"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("normal"))),
            )
            val expected = moves.maxWith(
                compareBy<BattleActionCandidate> { it.moveDetails!!.power * it.moveDetails!!.accuracy }
                    .thenByDescending { it.actionId },
            )

            assertNotEquals("forfeit", decision.actionId, "iteration=$iteration")
            assertEquals(expected.actionId, decision.actionId, "iteration=$iteration")
        }
    }

    @Test
    fun `healthy active attacks instead of switching back into a critical hp resistance`() {
        val criticalBenchId = UUID.randomUUID()
        val attack = move(
            id = "energy_ball",
            power = 90.0,
            typeId = "grass",
            facts = damageFacts(0.32),
        )

        assertEquals(
            "energy_ball",
            decide(
                candidates = listOf(attack, switch("critical_resistance", criticalBenchId)),
                turn = 7,
                allyHp = 0.917,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("water"),
                allyBench = mapOf(criticalBenchId to BenchPokemon(0.18, setOf("water"))),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 1,
                ),
            ).actionId,
        )
    }

    @Test
    fun `critical active attacks instead of spending the turn on another critical non immune target`() {
        val criticalBenchId = UUID.randomUUID()
        val attack = move(
            id = "energy_ball",
            power = 75.0,
            typeId = "grass",
            facts = damageFacts(0.435),
        )

        assertEquals(
            "energy_ball",
            decide(
                candidates = listOf(attack, switch("critical_fighting", criticalBenchId)),
                turn = 6,
                allyHp = 0.039,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("ground"),
                allyBench = mapOf(criticalBenchId to BenchPokemon(0.082, setOf("fighting"))),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 3,
                ),
            ).actionId,
        )
    }

    @Test
    fun `critical active attacks instead of switching into a publicly worse matchup`() {
        val weakBenchId = UUID.randomUUID()
        val attack = move(
            id = "surf",
            power = 90.0,
            typeId = "water",
            facts = damageFacts(0.16),
        )

        assertEquals(
            "surf",
            decide(
                candidates = listOf(attack, switch("weak_rock", weakBenchId)),
                turn = 7,
                allyHp = 0.197,
                allyTypes = setOf("water"),
                opponentTypes = setOf("water"),
                allyBench = mapOf(weakBenchId to BenchPokemon(1.0, setOf("rock"))),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 6,
                    switchesThisBattle = 1,
                ),
            ).actionId,
        )
    }

    @Test
    fun `team rotation cannot switch again on the turn after a voluntary switch when damage is available`() {
        val thirdPokemonId = UUID.randomUUID()
        val availableDamage = move(
            id = "available_damage",
            power = 60.0,
            typeId = "normal",
            facts = damageFacts(0.20),
        )

        assertEquals(
            "available_damage",
            decide(
                candidates = listOf(availableDamage, switch("rotate_to_third", thirdPokemonId)),
                turn = 9,
                allyHp = 0.15,
                allyTypes = setOf("fire"),
                opponentTypes = setOf("ground"),
                allyBench = mapOf(thirdPokemonId to BenchPokemon(1.0, setOf("flying"))),
                memory = BattleTacticalMemoryView(
                    turnsSinceLastSwitch = 1,
                    switchesThisBattle = 3,
                ),
            ).actionId,
        )
    }

    @Test
    fun `guaranteed public knockout finishes the opponent instead of taking a healthy switch`() {
        val benchId = UUID.randomUUID()
        val finisher = move(
            id = "surf",
            power = 90.0,
            typeId = "water",
            facts = guaranteedDamageFacts(0.32),
        )

        assertEquals(
            "surf",
            decide(
                candidates = listOf(finisher, switch("healthy_fire", benchId)),
                turn = 3,
                allyHp = 0.288,
                opponentHp = 0.141,
                allyTypes = setOf("water"),
                opponentTypes = setOf("fairy"),
                allyBench = mapOf(benchId to BenchPokemon(1.0, setOf("fire"))),
                memory = BattleTacticalMemoryView(
                    lastMoveId = "cobblemon:surf",
                    sameMoveRepeatCount = 2,
                ),
            ).actionId,
        )
    }

    @Test
    fun `secure public knockout outranks even a large defensive switch improvement`() {
        val immuneBenchId = UUID.randomUUID()
        val finisher = move(
            id = "secure_finish",
            power = 40.0,
            typeId = "water",
            facts = guaranteedDamageFacts(0.10),
        )

        assertEquals(
            "secure_finish",
            decide(
                candidates = listOf(finisher, switch("switch_to_immunity", immuneBenchId)),
                turn = 6,
                allyHp = 0.15,
                opponentHp = 0.08,
                allyTypes = setOf("water"),
                opponentTypes = setOf("electric"),
                allyBench = mapOf(immuneBenchId to BenchPokemon(1.0, setOf("ground"))),
            ).actionId,
        )
    }

    @Test
    fun `recent switch can switch again when public immunity materially improves survival`() {
        val immuneBenchId = UUID.randomUUID()

        assertEquals(
            "switch_to_immunity",
            decide(
                candidates = listOf(
                    move("available_damage", 60.0, facts = damageFacts(0.20)),
                    switch("switch_to_immunity", immuneBenchId),
                ),
                turn = 7,
                allyHp = 0.80,
                allyTypes = setOf("water"),
                opponentTypes = setOf("electric"),
                allyBench = mapOf(immuneBenchId to BenchPokemon(1.0, setOf("ground"))),
                memory = BattleTacticalMemoryView(turnsSinceLastSwitch = 1, switchesThisBattle = 2),
            ).actionId,
        )
    }

    @Test
    fun `hazard doomed active uses its final useful turn instead of preserving an unusable reserve`() {
        val safeBenchId = UUID.randomUUID()
        val finalAttack = move(
            id = "final_attack",
            power = 70.0,
            typeId = "grass",
            facts = damageFacts(0.25),
        )

        assertEquals(
            "final_attack",
            decide(
                candidates = listOf(finalAttack, switch("escape_to_water", safeBenchId)),
                allyHp = 0.10,
                allyTypes = setOf("grass"),
                opponentTypes = setOf("fire"),
                allyBench = mapOf(safeBenchId to BenchPokemon(1.0, setOf("water"))),
                field = sideConditionField(BattleSide.ALLY, "stealthrock"),
            ).actionId,
        )
    }

    @Test
    fun `regenerator recovery prevents a false fatal reentry sacrifice`() {
        val safeBenchId = UUID.randomUUID()

        assertEquals(
            "escape_to_water",
            decide(
                candidates = listOf(
                    move("final_attack", 70.0, typeId = "grass", facts = damageFacts(0.25)),
                    switch("escape_to_water", safeBenchId),
                ),
                allyHp = 0.10,
                allyTypes = setOf("grass"),
                opponentTypes = setOf("fire"),
                allyAbilityId = "regenerator",
                allyBench = mapOf(safeBenchId to BenchPokemon(1.0, setOf("water"))),
                field = sideConditionField(BattleSide.ALLY, "stealthrock"),
            ).actionId,
        )
    }

    @Test
    fun `boosted active keeps its setup when a credible attack survives the known response`() {
        val immuneBenchId = UUID.randomUUID()
        val boostedAttack = move(
            id = "boosted_attack",
            power = 75.0,
            typeId = "water",
            facts = damageFacts(0.30),
        )

        assertEquals(
            "boosted_attack",
            decide(
                candidates = listOf(boostedAttack, switch("optional_immunity", immuneBenchId)),
                allyHp = 0.80,
                allyTypes = setOf("water"),
                opponentTypes = setOf("electric"),
                allyStatStages = mapOf("attack" to 2),
                allyBench = mapOf(immuneBenchId to BenchPokemon(1.0, setOf("ground"))),
            ).actionId,
        )
    }

    @Test
    fun `boosted active may switch when its only attack is publicly nullified`() {
        val darkBenchId = UUID.randomUUID()
        val nullifiedAttack = move(
            id = "nullified_normal_attack",
            power = 100.0,
            typeId = "normal",
            facts = damageFacts(0.50),
        )

        assertEquals(
            "switch_to_dark",
            decide(
                candidates = listOf(nullifiedAttack, switch("switch_to_dark", darkBenchId)),
                allyHp = 0.80,
                allyTypes = setOf("normal"),
                opponentTypes = setOf("ghost"),
                allyStatStages = mapOf("attack" to 4),
                allyBench = mapOf(darkBenchId to BenchPokemon(1.0, setOf("dark"))),
            ).actionId,
        )
    }

    @Test
    fun `damage roll knockout bonus remains weighted by public accuracy`() {
        val inaccurateFinisher = move(
            id = "inaccurate_finisher",
            power = 120.0,
            accuracy = 10.0,
            facts = guaranteedDamageFacts(0.20, accuracy = 0.10),
        )
        val reliableDamage = move(
            id = "reliable_damage",
            power = 80.0,
            facts = damageFacts(0.40),
        )

        assertEquals(
            "reliable_damage",
            decide(listOf(inaccurateFinisher, reliableDamage)).actionId,
        )
    }

    @Test
    fun `public damage roll knockout chance can finish a weak target over larger chip elsewhere`() {
        val finisher = move(
            id = "finish_slot_zero",
            power = 70.0,
            targetSlot = 0,
            facts = projectedDamageFacts(0.20, 0.36, 0.75, BattleKnockoutAssessment.POSSIBLE),
        )
        val largerChip = move(
            id = "chip_slot_one",
            power = 90.0,
            targetSlot = 1,
            facts = projectedDamageFacts(0.36, 0.36, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
        )

        assertEquals(
            "finish_slot_zero",
            decide(
                candidates = listOf(largerChip, finisher),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = emptySet(),
                opponentHp = 0.30,
                opponentPartnerTypes = emptySet(),
                opponentPartnerHp = 1.0,
            ).actionId,
        )
    }

    @Test
    fun `major status pressure beats modest chip against a healthy unstatused target`() {
        val status = statusMove("apply_paralysis")
        val chip = move("quarter_chip", power = 60.0, facts = damageFacts(0.25))

        assertEquals("apply_paralysis", decide(listOf(chip, status)).actionId)
    }

    @Test
    fun `major status is not reapplied to an already statused public target`() {
        val status = statusMove("reapply_paralysis")
        val chip = move("small_chip", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "small_chip",
            decide(listOf(status, chip), opponentStatusId = "cobblemon:paralysis").actionId,
        )
    }

    @Test
    fun `toxic into a public steel target is removed while a useful move exists`() {
        val toxic = statusMove("toxic", statusId = "cobblemon:tox")
        val chip = move("neutral_chip", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "neutral_chip",
            decide(listOf(toxic, chip), opponentTypes = setOf("steel")).actionId,
        )
    }

    @Test
    fun `active non stackable ally side conditions are not used again`() {
        listOf("tailwind", "reflect", "lightscreen", "auroraveil").forEach { effectId ->
            val sideCondition = sideConditionMove(
                id = "${effectId}_again",
                effectId = effectId,
                target = BattleMoveEffectTarget.USER_SIDE,
            )
            val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

            assertEquals(
                "small_damage",
                decide(
                    candidates = listOf(sideCondition, chip),
                    field = sideConditionField(BattleSide.ALLY, effectId, remainingTurns = 3),
                ).actionId,
                effectId,
            )
        }
    }

    @Test
    fun `ally side condition expiring this turn may be refreshed`() {
        val tailwind = sideConditionMove(
            id = "tailwind",
            effectId = "tailwind",
            target = BattleMoveEffectTarget.USER_SIDE,
        )

        assertEquals(
            "tailwind",
            decide(
                candidates = listOf(tailwind, move("chip", 40.0, facts = damageFacts(0.10))),
                field = sideConditionField(BattleSide.ALLY, "tailwind", remainingTurns = 1),
            ).actionId,
        )
    }

    @Test
    fun `stackable opponent side condition can still add another layer`() {
        val spikes = sideConditionMove(
            id = "add_spikes_layer",
            effectId = "spikes",
            target = BattleMoveEffectTarget.TARGET_SIDE,
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "add_spikes_layer",
            decide(
                candidates = listOf(spikes, chip),
                field = sideConditionField(BattleSide.OPPONENT, "spikes", stacks = 1),
            ).actionId,
        )
    }

    @Test
    fun `same side condition on the opponent does not block setting it for allies`() {
        val tailwind = sideConditionMove(
            id = "ally_tailwind",
            effectId = "tailwind",
            target = BattleMoveEffectTarget.USER_SIDE,
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "ally_tailwind",
            decide(
                candidates = listOf(tailwind, chip),
                field = sideConditionField(BattleSide.OPPONENT, "tailwind", remainingTurns = 3),
            ).actionId,
        )
    }

    @Test
    fun `one active screen is enough before returning to useful damage`() {
        val lightScreen = sideConditionMove(
            id = "set_light_screen",
            effectId = "lightscreen",
            target = BattleMoveEffectTarget.USER_SIDE,
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals("set_light_screen", decide(listOf(lightScreen, chip)).actionId)
        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(lightScreen, chip),
                field = sideConditionField(BattleSide.ALLY, "reflect", remainingTurns = 3),
            ).actionId,
        )
    }

    @Test
    fun `first active turn move is used on entry but not after the actor has already moved`() {
        val actorId = UUID.randomUUID()
        val fakeOut = firstActiveTurnMove("fake_out")
        val reliableDamage = move("reliable_damage", power = 60.0, facts = damageFacts(0.25))
        val priorMove = BattleObservedEventView(
            sequence = 0,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = actorId,
            publicValueId = "cobblemon:fake_out",
        )

        assertEquals(
            "reliable_damage",
            decide(
                candidates = listOf(fakeOut, reliableDamage),
                turn = 2,
                allyPokemonId = actorId,
                observedEvents = listOf(priorMove),
            ).actionId,
        )
        assertEquals(
            "fake_out",
            decide(
                candidates = listOf(fakeOut, reliableDamage),
                turn = 1,
                allyPokemonId = actorId,
            ).actionId,
        )
        assertEquals(
            "fake_out",
            decide(
                candidates = listOf(fakeOut, reliableDamage),
                turn = 3,
                allyPokemonId = actorId,
                observedEvents = listOf(
                    priorMove,
                    BattleObservedEventView(
                        sequence = 1,
                        turn = 3,
                        kind = BattleObservedEventKind.SWITCHED,
                        actorPokemonId = actorId,
                    ),
                ),
            ).actionId,
        )
    }

    @Test
    fun `active weather terrain and room are not selected again`() {
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))
        val cases = listOf(
            Triple(
                effectMove("reset_rain", BattleMoveEffectKind.WEATHER, "raindance"),
                field(weatherId = "raindance"),
                "weather",
            ),
            Triple(
                effectMove("reset_grassy_terrain", BattleMoveEffectKind.TERRAIN, "grassyterrain"),
                field(terrainId = "grassyterrain"),
                "terrain",
            ),
            Triple(
                effectMove("reset_trick_room", BattleMoveEffectKind.FIELD_CONDITION, "trickroom"),
                field(roomEffectId = "trickroom"),
                "room",
            ),
        )

        cases.forEach { (candidate, activeField, label) ->
            assertEquals(
                "small_damage",
                decide(listOf(candidate, chip), field = activeField).actionId,
                label,
            )
        }
    }

    @Test
    fun `stat setup is used once then converts the boost into damage`() {
        val swordsDance = effectMove(
            id = "swords_dance",
            kind = BattleMoveEffectKind.STAT_STAGE,
            statStages = mapOf("attack" to 2),
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(swordsDance, chip),
                allyStatStages = mapOf("attack" to 6),
            ).actionId,
        )
        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(swordsDance, chip),
                allyStatStages = mapOf("attack" to 2),
            ).actionId,
        )
        assertEquals("swords_dance", decide(listOf(swordsDance, chip)).actionId)
    }

    @Test
    fun `stacking side conditions stop at their declared maximum`() {
        val spikes = sideConditionMove(
            id = "add_spikes_layer",
            effectId = "spikes",
            target = BattleMoveEffectTarget.TARGET_SIDE,
            maximumStacks = 3,
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "add_spikes_layer",
            decide(
                candidates = listOf(spikes, chip),
                field = sideConditionField(BattleSide.OPPONENT, "spikes", stacks = 2),
            ).actionId,
        )
        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(spikes, chip),
                field = sideConditionField(BattleSide.OPPONENT, "spikes", stacks = 3),
            ).actionId,
        )
    }

    @Test
    fun `known one layer hazards are not repeated without an explicit stack declaration`() {
        val stickyWeb = sideConditionMove(
            id = "sticky_web",
            effectId = "stickyweb",
            target = BattleMoveEffectTarget.TARGET_SIDE,
        )
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))

        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(stickyWeb, chip),
                field = sideConditionField(BattleSide.OPPONENT, "stickyweb", stacks = 1),
            ).actionId,
        )
    }

    @Test
    fun `publicly false weather status and hp requirements are rejected`() {
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))
        val auroraVeil = effectMove(
            id = "aurora_veil",
            kind = BattleMoveEffectKind.SIDE_CONDITION,
            valueId = "auroraveil",
            target = BattleMoveEffectTarget.USER_SIDE,
            requirements = listOf(
                BattleMoveRequirementView(
                    BattleMoveRequirementKind.WEATHER_ANY_OF,
                    setOf("hail", "snow"),
                ),
            ),
        )
        val sleepTalk = effectMove(
            id = "sleep_talk",
            kind = BattleMoveEffectKind.USABLE_WHILE_ASLEEP,
            requirements = listOf(
                BattleMoveRequirementView(BattleMoveRequirementKind.USER_STATUS_ANY_OF, setOf("slp")),
            ),
        )
        val substitute = effectMove(
            id = "substitute",
            kind = BattleMoveEffectKind.VOLATILE_STATUS,
            valueId = "substitute",
            target = BattleMoveEffectTarget.USER,
            requirements = listOf(
                BattleMoveRequirementView(BattleMoveRequirementKind.USER_HP_ABOVE_FRACTION, threshold = 0.25),
            ),
        )

        assertEquals("small_damage", decide(listOf(auroraVeil, chip)).actionId)
        assertEquals("aurora_veil", decide(listOf(auroraVeil, chip), field = field(weatherId = "snow")).actionId)
        assertEquals("small_damage", decide(listOf(sleepTalk, chip)).actionId)
        assertEquals("sleep_talk", decide(listOf(sleepTalk, chip), allyStatusId = "slp").actionId)
        assertEquals("small_damage", decide(listOf(substitute, chip), allyHp = 0.25).actionId)
        assertEquals("substitute", decide(listOf(substitute, chip), allyHp = 0.80).actionId)
    }

    @Test
    fun `a recently failed scripted move is not immediately repeated`() {
        val actorId = UUID.randomUUID()
        val scripted = effectMove("scripted_status", BattleMoveEffectKind.VOLATILE_STATUS, "custom")
        val chip = move("small_damage", power = 40.0, facts = damageFacts(0.15))
        val events = listOf(
            BattleObservedEventView(
                sequence = 0,
                turn = 2,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = actorId,
                publicValueId = "cobblemon:scripted_status",
            ),
            BattleObservedEventView(
                sequence = 1,
                turn = 2,
                kind = BattleObservedEventKind.MOVE_OUTCOME,
                actorPokemonId = actorId,
                moveOutcome = BattleMoveOutcomeView(
                    kind = BattleMoveOutcomeKind.FAILED,
                    moveId = "cobblemon:scripted_status",
                ),
            ),
        )

        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(scripted, chip),
                turn = 3,
                allyPokemonId = actorId,
                observedEvents = events,
            ).actionId,
        )
    }

    @Test
    fun `consecutive protection and charge turns pay their real tempo cost`() {
        val protect = effectMove("protect", BattleMoveEffectKind.PROTECT_USER, "protect")
        val detect = effectMove("detect", BattleMoveEffectKind.PROTECT_USER, "detect")
        val actorId = UUID.randomUUID()
        val chargeAttack = move(
            id = "solar_beam",
            power = 120.0,
            facts = damageFacts(0.30),
            effects = effects(BattleMoveEffectView(BattleMoveEffectKind.CHARGE_TURN, BattleMoveEffectTarget.USER)),
        )
        val chip = move("small_damage", power = 60.0, facts = damageFacts(0.20))

        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(protect, chip),
                allyPokemonId = actorId,
                observedEvents = protectionHistory(actorId, "protect"),
                memory = BattleTacticalMemoryView(
                    activePlan = null,
                    tendencies = emptyList(),
                    turnsSinceLastSwitch = Int.MAX_VALUE,
                    switchesThisBattle = 0,
                    lastMoveId = "cobblemon:protect",
                    sameMoveRepeatCount = 1,
                ),
            ).actionId,
        )
        assertEquals(
            "small_damage",
            decide(
                candidates = listOf(protect, chip),
                turn = 2,
                allyPokemonId = actorId,
                observedEvents = protectionHistory(actorId, "protect", "detect"),
                memory = BattleTacticalMemoryView(
                    lastMoveId = "cobblemon:detect",
                    sameMoveRepeatCount = 1,
                ),
            ).actionId,
        )
        assertEquals("small_damage", decide(listOf(chargeAttack, chip)).actionId)
    }

    private fun protectionHistory(actorId: UUID, vararg moveIds: String): List<BattleObservedEventView> =
        moveIds.flatMapIndexed { index, moveId ->
            val turn = index
            val sequence = index.toLong() * 2L + 1L
            listOf(
                BattleObservedEventView(
                    sequence = sequence,
                    turn = turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = actorId,
                    publicValueId = "cobblemon:$moveId",
                ),
                BattleObservedEventView(
                    sequence = sequence + 1L,
                    turn = turn,
                    kind = BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(actorId),
                    moveOutcome = BattleMoveOutcomeView(
                        kind = BattleMoveOutcomeKind.PROTECTION_STARTED,
                        publicEffectId = "protect",
                    ),
                ),
            )
        }

    @Test
    fun `moves declared unusable twice in a row are not repeated`() {
        val bloodMoon = move(
            id = "blood_moon",
            power = 140.0,
            facts = damageFacts(0.45),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = false,
                mechanicFlags = setOf("cantusetwice"),
            ),
        )
        val reliable = move("reliable_damage", power = 80.0, facts = damageFacts(0.25))

        assertEquals(
            "reliable_damage",
            decide(
                candidates = listOf(bloodMoon, reliable),
                memory = BattleTacticalMemoryView(
                    tendencies = emptyList(),
                    turnsSinceLastSwitch = Int.MAX_VALUE,
                    lastMoveId = "cobblemon:blood_moon",
                    sameMoveRepeatCount = 1,
                ),
            ).actionId,
        )
    }

    @Test
    fun `switch that publicly faints on entry loses to a surviving defensive switch`() {
        val doomedId = UUID.randomUUID()
        val safeId = UUID.randomUUID()

        assertEquals(
            "safe_switch",
            decide(
                candidates = listOf(
                    switch("doomed_switch", doomedId, facts = switchFacts(1.0)),
                    switch("safe_switch", safeId, facts = switchFacts(0.10)),
                ),
                allyHp = 0.15,
                allyTypes = setOf("fire", "flying"),
                opponentTypes = setOf("rock"),
                allyBench = mapOf(
                    doomedId to BenchPokemon(1.0, setOf("fighting")),
                    safeId to BenchPokemon(0.35, setOf("fire")),
                ),
            ).actionId,
        )
    }

    @Test
    fun `double focus fire is preferred when public minimum damage secures one knockout`() {
        val focus = composite(
            "z_focus_ko",
            move(
                "focus_first",
                power = 60.0,
                actorSlot = 0,
                targetSlot = 0,
                facts = projectedDamageFacts(0.25, 0.25, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
            move(
                "focus_second",
                power = 60.0,
                actorSlot = 1,
                targetSlot = 0,
                facts = projectedDamageFacts(0.25, 0.25, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
        )
        val split = composite(
            "a_split_chip",
            move(
                "split_first",
                power = 60.0,
                actorSlot = 0,
                targetSlot = 0,
                facts = projectedDamageFacts(0.25, 0.25, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
            move(
                "split_second",
                power = 90.0,
                actorSlot = 1,
                targetSlot = 1,
                facts = projectedDamageFacts(0.40, 0.40, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
        )

        assertEquals(
            "z_focus_ko",
            decide(
                candidates = listOf(split, focus),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = emptySet(),
                opponentHp = 0.45,
                opponentPartnerTypes = emptySet(),
                opponentPartnerHp = 1.0,
            ).actionId,
        )
    }

    @Test
    fun `double does not waste a second action on a target already guaranteed to faint`() {
        val redundantFocus = composite(
            "a_redundant_focus",
            move(
                "guaranteed_first",
                power = 100.0,
                actorSlot = 0,
                targetSlot = 0,
                facts = guaranteedDamageFacts(0.60),
            ),
            move(
                "redundant_second",
                power = 60.0,
                actorSlot = 1,
                targetSlot = 0,
                facts = projectedDamageFacts(0.25, 0.25, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
        )
        val split = composite(
            "z_split_after_ko",
            move(
                "guaranteed_split_first",
                power = 100.0,
                actorSlot = 0,
                targetSlot = 0,
                facts = guaranteedDamageFacts(0.60),
            ),
            move(
                "useful_second",
                power = 60.0,
                actorSlot = 1,
                targetSlot = 1,
                facts = projectedDamageFacts(0.25, 0.25, 0.0, BattleKnockoutAssessment.IMPOSSIBLE),
            ),
        )

        assertEquals(
            "z_split_after_ko",
            decide(
                candidates = listOf(redundantFocus, split),
                format = BattleFormat.DOUBLE,
                allyPartnerTypes = emptySet(),
                opponentHp = 0.40,
                opponentPartnerTypes = emptySet(),
                opponentPartnerHp = 1.0,
            ).actionId,
        )
    }

    private fun decide(
        candidates: List<BattleActionCandidate>,
        turn: Int = 1,
        allyPokemonId: UUID = UUID.randomUUID(),
        allyHp: Double = 1.0,
        opponentHp: Double = 1.0,
        opponentPokemonId: UUID = UUID.randomUUID(),
        allyTypes: Set<String> = emptySet(),
        opponentTypes: Set<String> = emptySet(),
        format: BattleFormat = BattleFormat.SINGLE,
        allyPartnerTypes: Set<String>? = null,
        allyPartnerHp: Double = 1.0,
        opponentPartnerTypes: Set<String>? = null,
        opponentPartnerHp: Double = 1.0,
        opponentStatusId: String? = null,
        allyAbilityId: String? = null,
        opponentAbilityId: String? = null,
        allyStatusId: String? = null,
        allyStatStages: Map<String, Int> = emptyMap(),
        field: BattleFieldStateView = BattleFieldStateView.empty(),
        observedEvents: List<BattleObservedEventView> = emptyList(),
        inferences: List<BattleInferenceView> = emptyList(),
        allyBench: Map<UUID, BenchPokemon> = emptyMap(),
        memory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
        selectedSession: BattleBrainSession = session,
    ) = brain.decide(selectedSession, contextOf(
        candidates, turn, allyPokemonId, allyHp, opponentHp, opponentPokemonId, allyTypes,
        opponentTypes, format, allyPartnerTypes, allyPartnerHp, opponentPartnerTypes,
        opponentPartnerHp, opponentStatusId, allyAbilityId, opponentAbilityId, allyStatusId,
        allyStatStages, field, observedEvents, inferences, allyBench, memory,
    )).toCompletableFuture().join()

    /** Same context [decide] builds, exposed so a decision can be inspected instead of only executed. */
    internal fun contextOf(
        candidates: List<BattleActionCandidate>,
        turn: Int = 1,
        allyPokemonId: UUID = UUID.randomUUID(),
        allyHp: Double = 1.0,
        opponentHp: Double = 1.0,
        opponentPokemonId: UUID = UUID.randomUUID(),
        allyTypes: Set<String> = emptySet(),
        opponentTypes: Set<String> = emptySet(),
        format: BattleFormat = BattleFormat.SINGLE,
        allyPartnerTypes: Set<String>? = null,
        allyPartnerHp: Double = 1.0,
        opponentPartnerTypes: Set<String>? = null,
        opponentPartnerHp: Double = 1.0,
        opponentStatusId: String? = null,
        allyAbilityId: String? = null,
        opponentAbilityId: String? = null,
        allyStatusId: String? = null,
        allyStatStages: Map<String, Int> = emptyMap(),
        field: BattleFieldStateView = BattleFieldStateView.empty(),
        observedEvents: List<BattleObservedEventView> = emptyList(),
        inferences: List<BattleInferenceView> = emptyList(),
        allyBench: Map<UUID, BenchPokemon> = emptyMap(),
        memory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
    ) = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = state(
                turn,
                allyPokemonId,
                allyHp,
                opponentHp,
                opponentPokemonId,
                allyTypes,
                opponentTypes,
                format,
                allyPartnerTypes,
                allyPartnerHp,
                opponentPartnerTypes,
                opponentPartnerHp,
                opponentStatusId,
                allyAbilityId,
                opponentAbilityId,
                allyStatusId,
                allyStatStages,
                field,
                observedEvents,
                inferences,
                allyBench,
            ),
            candidates = candidates,
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = memory,
        )

    internal fun move(
        id: String,
        power: Double,
        accuracy: Double = 100.0,
        mechanic: String? = null,
        typeId: String = "normal",
        targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        priority: Int = 0,
        damageCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        actorSlot: Int = 0,
        targetSlot: Int = 0,
        effects: BattleMoveEffectsView? = null,
        facts: BattleCandidateFactsView? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
        moveDetails = BattleMoveCandidateView(
            typeId = typeId,
            damageCategory = damageCategory,
            power = power,
            accuracy = accuracy,
            priority = priority,
            currentPp = 10,
            targetPattern = targetPattern,
            effects = effects,
        ),
        facts = facts,
    )

    internal fun statusMove(
        id: String,
        targetSlot: Int = 0,
        statusId: String = "cobblemon:paralysis",
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.STATUS,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0,
                        valueId = statusId,
                    ),
                ),
                scriptedBehavior = false,
            ),
        ),
        facts = BattleCandidateFactsView(
            baseAccuracyProbability = 1.0,
            statusEffectProbability = 1.0,
            calculationCoverage = BattleCalculationCoverage.PARTIAL,
        ),
    )

    private fun sideConditionMove(
        id: String,
        effectId: String,
        target: BattleMoveEffectTarget,
        maximumStacks: Int? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        moveDetails = BattleMoveCandidateView(
            typeId = "flying",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SIDE,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.SIDE_CONDITION,
                        target = target,
                        valueId = effectId,
                        amountRange = maximumStacks?.let { jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange(1, it) },
                    ),
                ),
                scriptedBehavior = false,
            ),
        ),
    )

    private fun firstActiveTurnMove(id: String) = move(
        id = id,
        power = 40.0,
        priority = 3,
        effects = BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = listOf(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY,
                    target = BattleMoveEffectTarget.USER,
                ),
            ),
            scriptedBehavior = true,
        ),
        facts = damageFacts(0.20),
    )

    private fun sideConditionField(
        side: BattleSide,
        effectId: String,
        remainingTurns: Int? = null,
        stacks: Int? = null,
    ) = BattleFieldStateView(
        weather = null,
        terrain = null,
        roomEffects = emptyList(),
        globalEffects = emptyList(),
        sideConditions = BattleSide.entries.associateWith { candidateSide ->
            if (candidateSide == side) {
                listOf(BattleTimedEffectView(effectId, remainingTurns, stacks))
            } else {
                emptyList()
            }
        },
    )

    private fun field(
        weatherId: String? = null,
        terrainId: String? = null,
        roomEffectId: String? = null,
    ) = BattleFieldStateView(
        weather = weatherId?.let { BattleTimedEffectView(it, 3) },
        terrain = terrainId?.let { BattleTimedEffectView(it, 3) },
        roomEffects = roomEffectId?.let { listOf(BattleTimedEffectView(it, 3)) }.orEmpty(),
        globalEffects = emptyList(),
        sideConditions = BattleSide.entries.associateWith { emptyList() },
    )

    private fun effectMove(
        id: String,
        kind: BattleMoveEffectKind,
        valueId: String? = null,
        target: BattleMoveEffectTarget = BattleMoveEffectTarget.USER,
        statStages: Map<String, Int> = emptyMap(),
        requirements: List<BattleMoveRequirementView> = emptyList(),
    ) = move(
        id = id,
        power = 0.0,
        damageCategory = BattleMoveDamageCategory.STATUS,
        effects = effects(
            BattleMoveEffectView(
                kind = kind,
                target = target,
                valueId = valueId,
                statStages = statStages,
            ),
            requirements = requirements,
        ),
    )

    private fun effects(
        vararg effects: BattleMoveEffectView,
        requirements: List<BattleMoveRequirementView> = emptyList(),
    ) = BattleMoveEffectsView(
        coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        effects = effects.toList(),
        scriptedBehavior = true,
        requirements = requirements,
    )

    internal fun damageFacts(fraction: Double) = BattleCandidateFactsView(
        baseAccuracyProbability = 1.0,
        standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
        standardDamageFractionRange = BattleDamageFractionRange(fraction, fraction),
        standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0),
        standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )

    internal fun guaranteedDamageFacts(fraction: Double, accuracy: Double = 1.0) = BattleCandidateFactsView(
        baseAccuracyProbability = accuracy,
        standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
        standardDamageFractionRange = BattleDamageFractionRange(fraction, fraction),
        standardDamageRollKoProbabilityRange = BattleFractionRange(1.0, 1.0),
        standardKnockoutAssessment = BattleKnockoutAssessment.GUARANTEED,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )

    private fun projectedDamageFacts(
        minimum: Double,
        maximum: Double,
        knockoutProbability: Double,
        assessment: BattleKnockoutAssessment,
    ) = BattleCandidateFactsView(
        baseAccuracyProbability = 1.0,
        standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
        standardDamageFractionRange = BattleDamageFractionRange(minimum, maximum),
        standardDamageRollKoProbabilityRange = BattleFractionRange(knockoutProbability, knockoutProbability),
        standardKnockoutAssessment = assessment,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )

    private fun switchFacts(entryHpLoss: Double) = BattleCandidateFactsView(
        switchEntryHpLossFraction = entryHpLoss,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )

    internal fun switch(
        id: String,
        pokemonId: UUID,
        actorSlot: Int = 0,
        facts: BattleCandidateFactsView? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.SWITCH,
        actorSlot = actorSlot,
        switchPokemonId = pokemonId,
        facts = facts,
    )

    private fun composite(id: String, vararg components: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = components.map { it.actionId },
        componentActions = components.toList(),
    )

    private fun state(
        turn: Int,
        allyPokemonId: UUID,
        allyHp: Double,
        opponentHp: Double,
        opponentPokemonId: UUID,
        allyTypes: Set<String>,
        opponentTypes: Set<String>,
        format: BattleFormat,
        allyPartnerTypes: Set<String>?,
        allyPartnerHp: Double,
        opponentPartnerTypes: Set<String>?,
        opponentPartnerHp: Double,
        opponentStatusId: String?,
        allyAbilityId: String?,
        opponentAbilityId: String?,
        allyStatusId: String?,
        allyStatStages: Map<String, Int>,
        field: BattleFieldStateView,
        observedEvents: List<BattleObservedEventView>,
        inferences: List<BattleInferenceView>,
        allyBench: Map<UUID, BenchPokemon>,
    ): BattleStateView {
        val ally = pokemon(
            BattleSide.ALLY,
            allyHp,
            allyTypes,
            pokemonId = allyPokemonId,
            statusId = allyStatusId,
            statStages = allyStatStages,
            knownAbilityId = allyAbilityId,
        )
        val opponent = pokemon(
            BattleSide.OPPONENT,
            opponentHp,
            opponentTypes,
            pokemonId = opponentPokemonId,
            statusId = opponentStatusId,
            knownAbilityId = opponentAbilityId,
        )
        val partner = allyPartnerTypes?.let {
            pokemon(BattleSide.ALLY, allyPartnerHp, it, activeSlot = 1)
        }
        val opponentPartner = opponentPartnerTypes?.let {
            pokemon(BattleSide.OPPONENT, opponentPartnerHp, it, activeSlot = 1)
        }
        val bench = allyBench.map { (pokemonId, state) ->
            pokemon(
                BattleSide.ALLY,
                state.hp,
                state.types,
                pokemonId = pokemonId,
                activeSlot = null,
                speciesId = state.speciesId,
            )
        }
        val pokemon = listOfNotNull(ally, partner, opponent, opponentPartner) + bench
        return BattleStateView(
            battleId = UUID.randomUUID(),
            format = format,
            turn = turn,
            pokemon = pokemon,
            field = field,
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                pokemon.count { it.side == side && !it.fainted }
            },
            observedEvents = observedEvents,
            inferences = inferences,
        )
    }

    private fun pokemon(
        side: BattleSide,
        hp: Double,
        knownTypeIds: Set<String> = emptySet(),
        pokemonId: UUID = UUID.randomUUID(),
        activeSlot: Int? = 0,
        speciesId: String = "showdown:test",
        statusId: String? = null,
        statStages: Map<String, Int> = emptyMap(),
        knownAbilityId: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = pokemonId,
        side = side,
        activeSlot = activeSlot,
        speciesId = speciesId,
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = emptySet(),
        knownAbilityId = knownAbilityId,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = knownTypeIds,
    )

    internal data class BenchPokemon(
        val hp: Double,
        val types: Set<String> = emptySet(),
        val speciesId: String = "showdown:test",
    )
}
