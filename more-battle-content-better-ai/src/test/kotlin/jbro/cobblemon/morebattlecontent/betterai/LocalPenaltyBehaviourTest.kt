package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Checks that each situational penalty is large enough to change what gets played.
 *
 * The characterization test that came before this one established that the self-play fixture
 * exercises none of these guards - it printed `COVERAGE: 0 of 9` - so nothing was watching them. That
 * matters more than it sounds, because the risk they carry is not deletion but calibration: the
 * magnitudes were tuned against heuristic-scale scores, and anything that rescales the comparison can
 * leave a penalty intact and simultaneously irrelevant.
 *
 * Each case is a controlled pair. Two candidates carry the same move template and differ only in the
 * trait the penalty punishes, so the penalty is the only thing separating them and the assertion is
 * about magnitude rather than about scoring in general. A penalty that still fires but no longer
 * outweighs a twin will fail here, which a report of non-zero values would not catch.
 *
 * This exists because of what happened in the selector work: a change measured as a large gain in
 * trainer character turned out to be the AI playing worse moves, and the only thing that noticed was
 * an old behaviour test. Every diversity number this project produces needs a paired check that the
 * play did not get worse, and for the penalties, this is that check.
 */
class LocalPenaltyBehaviourTest {
    @Test
    fun `a saturated stat boost loses to the identical unsaturated one`() {
        // Attack is already at the cap, so the boost cannot do anything; special attack has room.
        val context = context(
            actorStages = mapOf("attack" to 6, "specialattack" to 0),
            candidates = listOf(
                statusMove("saturated", statStages = mapOf("attack" to 2)),
                statusMove("useful", statStages = mapOf("specialattack" to 2)),
            ),
        )
        assertPenalised(context, "saturated", LocalTacticalSituationalEvaluator::saturatedStatStagePenalty)
    }

    @Test
    fun `a charge turn move loses to the identical immediate one`() {
        val context = context(
            candidates = listOf(
                statusMove("charged", extraEffects = listOf(effect(BattleMoveEffectKind.CHARGE_TURN))),
                statusMove("immediate"),
            ),
        )
        assertPenalised(context, "charged", LocalTacticalSituationalEvaluator::forcedTempoPenalty)
    }

    @Test
    fun `a move that cannot be used twice loses after it has just been used`() {
        val context = context(
            memory = BattleTacticalMemoryView(lastMoveId = "cobblemon:repeated", sameMoveRepeatCount = 2),
            candidates = listOf(
                statusMove("repeated", mechanicFlags = setOf("cantusetwice")),
                statusMove("fresh"),
            ),
        )
        assertPenalised(context, "repeated", LocalTacticalSituationalEvaluator::consecutiveUseForbiddenPenalty)
    }

    @Test
    fun `a move needing a pending damaging move loses to one that needs nothing`() {
        val context = context(
            candidates = listOf(
                statusMove(
                    "conditional",
                    requirements = listOf(
                        BattleMoveRequirementView(BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE),
                    ),
                ),
                statusMove("unconditional"),
            ),
        )
        assertPenalised(context, "conditional", LocalTacticalSituationalEvaluator::pendingDamagingMoveRiskPenalty)
    }

    @Test
    fun `a move whose public requirement is unmet loses to one that needs nothing`() {
        // The actor carries no status, so a move that requires one cannot work.
        val context = context(
            candidates = listOf(
                statusMove(
                    "needs_status",
                    requirements = listOf(
                        BattleMoveRequirementView(BattleMoveRequirementKind.USER_STATUS_PRESENT),
                    ),
                ),
                statusMove("needs_nothing"),
            ),
        )
        assertPenalised(context, "needs_status", LocalTacticalSituationalEvaluator::unmetPublicRequirementPenalty)
    }

    @Test
    fun `re-setting weather that is already up loses to setting something new`() {
        val context = context(
            weather = BattleTimedEffectView(effectId = "raindance", remainingTurns = 4),
            candidates = listOf(
                statusMove("re_rain", extraEffects = listOf(weatherEffect("raindance"))),
                statusMove("set_sun", extraEffects = listOf(weatherEffect("sunnyday"))),
            ),
        )
        assertPenalised(
            context,
            "re_rain",
            LocalTacticalSituationalEvaluator::activePersistentEffectRefreshPenalty,
        )
    }

    @Test
    fun `a move that just publicly failed loses to one that has not`() {
        val actorId = UUID.randomUUID()
        val context = context(
            actorId = actorId,
            observedEvents = listOf(
                BattleObservedEventView(
                    sequence = 10L,
                    turn = 3,
                    kind = BattleObservedEventKind.MOVE_OUTCOME,
                    actorPokemonId = actorId,
                    moveOutcome = BattleMoveOutcomeView(
                        kind = BattleMoveOutcomeKind.FAILED,
                        moveId = "cobblemon:just_failed",
                    ),
                ),
            ),
            candidates = listOf(
                statusMove("just_failed"),
                statusMove("untried"),
            ),
        )
        assertPenalised(context, "just_failed", LocalTacticalSituationalEvaluator::recentPublicFailurePenalty)
    }

    @Test
    fun `a first-turn-only move loses once the user has been out for a while`() {
        val actorId = UUID.randomUUID()
        val context = context(
            actorId = actorId,
            observedEvents = listOf(
                BattleObservedEventView(
                    sequence = 2L,
                    turn = 1,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = actorId,
                ),
                BattleObservedEventView(
                    sequence = 9L,
                    turn = 3,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = actorId,
                    publicValueId = "cobblemon:any_turn",
                ),
            ),
            candidates = listOf(
                statusMove("first_turn_only", extraEffects = listOf(effect(BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY))),
                statusMove("any_turn"),
            ),
        )
        assertPenalised(
            context,
            "first_turn_only",
            LocalTacticalSituationalEvaluator::expiredFirstActiveTurnPenalty,
        )
    }

    @Test
    fun `protecting again after a successful protect loses to acting`() {
        val actorId = UUID.randomUUID()
        // One prior turn of Protect that publicly started, so the chain is live and the next attempt
        // is already only a third likely to work.
        val context = context(
            actorId = actorId,
            observedEvents = listOf(
                BattleObservedEventView(
                    sequence = 5L,
                    turn = 3,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = actorId,
                    publicValueId = "cobblemon:protect_again",
                ),
                BattleObservedEventView(
                    sequence = 6L,
                    turn = 3,
                    kind = BattleObservedEventKind.MOVE_OUTCOME,
                    actorPokemonId = actorId,
                    targetPokemonIds = listOf(actorId),
                    // The contract forbids naming a causing move on this outcome: a public protection
                    // signal does not identify what produced it.
                    moveOutcome = BattleMoveOutcomeView(
                        kind = BattleMoveOutcomeKind.PROTECTION_STARTED,
                        publicEffectId = "protect",
                    ),
                ),
            ),
            candidates = listOf(
                statusMove("protect_again", extraEffects = listOf(effect(BattleMoveEffectKind.PROTECT_USER))),
                statusMove("act_instead"),
            ),
        )
        assertPenalised(context, "protect_again", LocalTacticalSituationalEvaluator::repeatedProtectionPenalty)
    }

    private fun weatherEffect(id: String) = BattleMoveEffectView(
        BattleMoveEffectKind.WEATHER,
        BattleMoveEffectTarget.USER,
        probability = 1.0,
        valueId = id,
    )

    /**
     * The penalty fires, and it is decisive against an otherwise identical action.
     *
     * Both parts are asserted deliberately. A penalty can survive a rescaling of the comparison and
     * still stop mattering, and only the second assertion notices that.
     */
    private fun assertPenalised(
        context: BattleDecisionContext,
        penalisedActionId: String,
        penalty: (BattleActionCandidate, BattleDecisionContext) -> Double,
    ) {
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val penalised = calculated.candidates.single { it.actionId == penalisedActionId }
        val applied = penalty(penalised, context)
        assertTrue(applied > 0.0, "$penalisedActionId was not penalised at all")

        val ranked = LocalBattleActionPolicy.rank(calculated, null, PROFILE)
        val best = ranked.first().outcome.candidate.actionId
        assertEquals(
            calculated.candidates.first { it.actionId != penalisedActionId }.actionId,
            best,
            "penalty of $applied did not outweigh an otherwise identical action",
        )
    }

    private fun effect(
        kind: BattleMoveEffectKind,
        target: BattleMoveEffectTarget = BattleMoveEffectTarget.USER,
        statStages: Map<String, Int> = emptyMap(),
    ) = BattleMoveEffectView(kind, target, probability = 1.0, statStages = statStages)

    private fun statusMove(
        id: String,
        statStages: Map<String, Int> = emptyMap(),
        extraEffects: List<BattleMoveEffectView> = emptyList(),
        requirements: List<BattleMoveRequirementView> = emptyList(),
        mechanicFlags: Set<String> = emptySet(),
    ): BattleActionCandidate {
        val effects = buildList {
            if (statStages.isNotEmpty()) {
                add(effect(BattleMoveEffectKind.STAT_STAGE, statStages = statStages))
            }
            addAll(extraEffects)
        }
        return BattleActionCandidate(
            actionId = id,
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:$id",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
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
                    effects = effects,
                    scriptedBehavior = false,
                    requirements = requirements,
                    mechanicFlags = mechanicFlags,
                ),
            ),
        )
    }

    private fun context(
        candidates: List<BattleActionCandidate>,
        actorStages: Map<String, Int> = emptyMap(),
        memory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
        weather: BattleTimedEffectView? = null,
        observedEvents: List<BattleObservedEventView> = emptyList(),
        actorId: UUID = UUID.randomUUID(),
    ): BattleDecisionContext {
        val pokemon = listOf(
            pokemon(BattleSide.ALLY, actorStages, actorId),
            pokemon(BattleSide.OPPONENT, emptyMap()),
        )
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.SINGLE,
                turn = 4,
                pokemon = pokemon,
                field = BattleFieldStateView(
                    weather = weather,
                    terrain = null,
                    roomEffects = emptyList(),
                    globalEffects = emptyList(),
                    sideConditions = BattleSide.entries.associateWith { emptyList() },
                ),
                remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                    pokemon.count { it.side == side }
                },
                observedEvents = observedEvents,
                inferences = emptyList(),
            ),
            candidates = candidates,
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = memory,
        )
    }

    private fun pokemon(
        side: BattleSide,
        stages: Map<String, Int>,
        id: UUID = UUID.randomUUID(),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = stages,
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(160, 160),
            attack = BattleIntegerRange(120, 120),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(120, 120),
            specialDefence = BattleIntegerRange(100, 100),
            speed = BattleIntegerRange(100, 100),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )

    private companion object {
        val PROFILE = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles.STANDARD,
        )
    }
}
