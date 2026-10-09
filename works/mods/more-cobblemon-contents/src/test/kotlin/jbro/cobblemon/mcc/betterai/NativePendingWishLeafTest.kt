package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.mcc.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconciliation
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.mcc.betterai.search.NativeInformationSetSearch
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A factory Alomomola (Flip Turn, Protect, Scald, Wish) at STANDARD protected turn after turn in a live battle: its
 * one-turn search never saw Wish land, so Wish looked like a wasted turn. After one Protect it must now Wish.
 */
class NativePendingWishLeafTest {
    @Test
    fun `a STANDARD Alomomola wishes rather than protecting twice in a row`() {
        val worker = EngineBranchWorker()
        val definition = battle()
        val root = worker.createBattle(definition)
        val protected = worker.branch(root.snapshotJson, move(root, BattleSide.ALLY, "protect"), move(root, BattleSide.OPPONENT, "hypervoice"))
        assertTrue(protected.field.pendingHeals.orEmpty().isEmpty())
        val values = rootValues(worker, definition, protected, worlds = 12)
        assertTrue(values.getValue("wish") > values.getValue("protect"), "Wish must beat a second Protect: $values")
        assertEquals("wish", values.maxBy { it.value }.key)

        val wished = worker.branch(protected.snapshotJson, move(protected, BattleSide.ALLY, "wish"), move(protected, BattleSide.OPPONENT, "calmmind"))
        assertEquals(listOf(ALLY.toString()), wished.field.pendingHeals.orEmpty().map { it.targetUuid })
    }

    private fun rootValues(worker: EngineBranchWorker, definition: NativeBattleDefinition, frame: NativeBattleFrame, worlds: Int): Map<String, Double> {
        val roots = (0 until worlds).map { if (it == 0) frame else worker.reseed(frame.snapshotJson, it) }
        val tree = NativeShowdownSearchTree(worker, frame, template())
        val candidates = tree.actions(tree.root, BattleSide.ALLY)
        val context = BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("pending-wish".toByteArray()),
            state = tree.root.state, candidates = candidates, deadlineEpochMillis = 5_000L,
        )
        val session = NativeProductSessionState(
            battleId = context.state.battleId, format = context.state.format, rulesFingerprint = worker.rulesFingerprint,
            worlds = roots.mapIndexed { i, start ->
                NativeProductSessionWorld(NativeSearchWorldKey("w", i), 1.0 / worlds, definition,
                    NativeProductRootSnapshot(worker.rulesFingerprint, start), context)
            },
            publicTurn = context.state.turn, lastObservedEventSequence = null,
        )
        val runner = NativeProductSearchRunner(nanoTime = { 5_000_000L }, lease = { _, action -> action(worker) })
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { _, _ -> error("no replanning") },
            searchWorlds = NativeInformationSetSearch(lease = { _, action -> action(worker) })::search,
            reconcileSession = { supplied, _, _ -> NativeProductSessionReconciliation(NativeProductSessionReconcileStatus.AVAILABLE, supplied) },
            nowEpochMillis = { 1_000L }, nanoTime = { 5_000_000L },
        )
        val evaluation = evaluator.evaluate(context, profile(), LocalDecisionTuning.CURRENT,
            LocalLookaheadBudgetPolicy.forTier(profile().difficulty.tier), session)
        assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status, "$evaluation")
        return evaluation.ranked.filter { it.outcome.candidate.mechanic == null }
            .associate { requireNotNull(it.outcome.candidate.moveId) to it.comparisonValue }
    }

    private fun profile() = BattleTrainerProfile.balanced(5, BattleDifficultyProfiles.STANDARD)

    private fun move(frame: NativeBattleFrame, side: BattleSide, moveId: String): String =
        NativeShowdownChoiceEncoder.encode(
            NativeShowdownRequestActionFactory.actions(side, frame).first { it.moveId == moveId && it.mechanic == null }, side, frame)

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(101, 103, 107, 109),
        p1Team = listOf(
            NativePokemonSet("Alomomola", "Alomomola", listOf("flipturn", "protect", "scald", "wish"), "Hydration", ALLY.toString(),
                item = "Heavy-Duty Boots", nature = "Careful", level = 50,
                evs = mapOf("hp" to 252, "atk" to 4, "def" to 0, "spa" to 0, "spd" to 252, "spe" to 0)),
        ),
        p2Team = listOf(
            NativePokemonSet("Sylveon", "Sylveon", listOf("hypervoice", "moonblast", "calmmind", "shadowball"), "Pixilate", OPPONENT.toString(),
                item = "Leftovers", nature = "Modest", level = 50,
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 4, "spa" to 252, "spd" to 0, "spe" to 0)),
        ),
    )

    private fun template() = BattleStateView(
        battleId = BATTLE, format = BattleFormat.SINGLE, turn = 1,
        pokemon = listOf(mon(ALLY, BattleSide.ALLY, "alomomola", setOf("water")), mon(OPPONENT, BattleSide.OPPONENT, "sylveon", setOf("fairy"))),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(id: UUID, side: BattleSide, species: String, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = 0, speciesId = "cobblemon:$species", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = types,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000002201")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000002202")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000002203")
    }
}
