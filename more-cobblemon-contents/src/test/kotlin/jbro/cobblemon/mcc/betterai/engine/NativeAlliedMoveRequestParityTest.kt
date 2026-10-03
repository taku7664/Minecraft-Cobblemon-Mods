package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NativeAlliedMoveRequestParityTest {
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val definition = NativeBattleDefinition(
        formatId = "cobblemondoubles", seed = listOf(11, 22, 33, 44),
        p1Team = listOf(
            NativePokemonSet("Florges", "Florges", listOf("pollenpuff", "protect"), "Flower Veil", uuid(1)),
            NativePokemonSet("Garchomp", "Garchomp", listOf("splash", "closecombat", "skillswap"), "Rough Skin", uuid(2))),
        p2Team = listOf(
            NativePokemonSet("Magikarp", "Magikarp", listOf("splash", "closecombat"), "Swift Swim", uuid(3)),
            NativePokemonSet("Magikarp", "Magikarp", listOf("splash"), "Swift Swim", uuid(4))),
        openingState = NativeBattleOpeningState(listOf(
            NativePokemonOpeningState(uuid(1), 153, 153, "Flower Veil", ""),
            NativePokemonOpeningState(uuid(2), 60, 183, "Rough Skin", ""),
            NativePokemonOpeningState(uuid(3), 95, 95, "Swift Swim", ""),
            NativePokemonOpeningState(uuid(4), 95, 95, "Swift Swim", ""))),
    )

    @Test
    fun `native allied Pollen Puff heals the partner like the current Showdown`() {
        val rootPath = System.getProperty("aiengine.showdown")?.let(Path::of)
        assumeTrue(rootPath != null && Files.isDirectory(rootPath), "No dev server Showdown")
        NativeShowdownBranchEngine.open(requireNotNull(rootPath)).use { oracle ->
            EngineBranchWorker().use { worker ->
                val expectedRoot = oracle.createBattle(definition)
                val root = worker.createBattle(definition)
                assertEquals("Special", root.p1Active.first().moves.first().category)
                val action = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root).single { turn ->
                    turn.componentActions.all { it.mechanic == null } &&
                        turn.componentActions[1].moveId == "splash" && turn.componentActions.first().let {
                        it.moveId == "pollenpuff" && it.mechanic == null && it.targets.singleOrNull()?.side == BattleSide.ALLY
                    }
                }
                val choice = NativeShowdownChoiceEncoder.encode(action, BattleSide.ALLY, root)
                assertEquals("move 1 -2, move 1", choice)
                val expected = oracle.branch(expectedRoot.snapshotJson, choice, "move 1, move 1")
                val actual = worker.branch(root.snapshotJson, choice, "move 1, move 1")
                assertEquals(151, actual.p1Active[1].hp)
                assertEquals(expected.p1Active[1].hp, actual.p1Active[1].hp)
                assertEquals(60, root.p1Active[1].hp)
                assertTrue(actual.p2Active.all { it.hp == 95 })
            }
        }
    }

    @Test
    fun `own native search preserves the live allied move policy after branching`() {
        EngineBranchWorker().use { worker ->
            val frame = worker.createBattle(definition)
            val pokemon = (frame.p1Active.map { it to BattleSide.ALLY } +
                frame.p2Active.map { it to BattleSide.OPPONENT }).map { (native, side) ->
                BattlePokemonStateView(
                    battlePokemonId = UUID.fromString(native.uuid), side = side, activeSlot = native.activeSlot,
                    speciesId = native.species, formId = null, level = 50,
                    hpFraction = native.hp.toDouble() / native.maxHp, statusId = null, statStages = emptyMap(),
                    knownMoveIds = native.moves.mapTo(HashSet()) { it.id }, knownAbilityId = native.ability,
                    knownHeldItemId = "", fainted = false,
                )
            }
            val template = BattleStateView(UUID.fromString(uuid(99)), BattleFormat.DOUBLE, 1, pokemon,
                BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2), emptyList(), emptyList())
            val tree = NativeShowdownSearchTree(worker, frame, template)
            fun hasAlliedMove(actions: List<jbro.cobblemon.mcc.internal.ai.BattleActionCandidate>, move: String) =
                actions.any { turn -> turn.componentActions.any {
                    it.moveId == move && it.targets.singleOrNull()?.side == BattleSide.ALLY
                } }
            assertTrue(hasAlliedMove(NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame), "closecombat"))
            fun verify(position: jbro.cobblemon.mcc.betterai.simulation.NativeSearchPosition) {
                val own = tree.actions(position, BattleSide.ALLY)
                assertTrue(!hasAlliedMove(own, "closecombat"))
                assertTrue(hasAlliedMove(own, "pollenpuff"))
                assertTrue(hasAlliedMove(own, "skillswap"))
                assertEquals(NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, position.frame).map { it.actionId },
                    tree.actions(position, BattleSide.OPPONENT).map { it.actionId })
            }
            verify(tree.root)
            val next = tree.branch(tree.root,
                tree.actions(tree.root, BattleSide.ALLY).first {
                    NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) == "move 2, move 1" },
                tree.actions(tree.root, BattleSide.OPPONENT).first {
                    NativeShowdownChoiceEncoder.encode(it, BattleSide.OPPONENT, frame) == "move 1, move 1" })
            verify(next)
        }
    }

    @Test
    fun `native requests preserve Max targets when starting and continuing Dynamax`() {
        EngineBranchWorker().use { worker ->
            val root = worker.createBattle(definition)
            fun maxTargets(requestJson: String): List<String> = JsonParser.parseString(requestJson).asJsonObject
                .getAsJsonArray("active")[0].asJsonObject.getAsJsonObject("maxMoves")
                ?.getAsJsonArray("maxMoves")?.map { it.asJsonObject.get("target").asString }.orEmpty()
            assertEquals(listOf("adjacentFoe", "self"), maxTargets(root.p1RequestJson))
            val next = worker.branch(root.snapshotJson, "move 1 1 dynamax, move 1", "move 1, move 1")
            assertEquals(listOf("adjacentFoe", "self"), maxTargets(next.p1RequestJson))
            val moves = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, next)
            assertTrue(moves.none { it.componentActions.first().mechanic?.mechanicId == "dynamax" })
            assertTrue(moves.filter { it.componentActions.first().moveId == "pollenpuff" }
                .all { it.componentActions.first().targets.single().side == BattleSide.OPPONENT })
            assertTrue(moves.filter { it.componentActions.first().moveId == "protect" }
                .all { it.componentActions.first().targets.isEmpty() })
        }
    }
}
