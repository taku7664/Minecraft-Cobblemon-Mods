package jbro.cobblemon.mcc.betterai.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.betterai.simulation.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NativeSubstituteLeafStateTest {
    private fun uuid(n: Long) = UUID(0, n)
    private val definition = NativeBattleDefinition("cobblemoncustomgame", listOf(11, 22, 33, 44),
        listOf(NativePokemonSet("Mew", "Mew", listOf("substitute", "splash"), "Synchronize", uuid(1).toString())),
        listOf(NativePokemonSet("Magikarp", "Magikarp", listOf("sonicboom"), "No Guard", uuid(2).toString())))

    @Test
    fun `native leaf preserves the damaged Substitute and clears it after the decoy breaks`() {
        val path = System.getProperty("aiengine.showdown")?.let(Path::of)
        assumeTrue(path != null && Files.isDirectory(path), "No dev server Showdown")
        NativeShowdownBranchEngine.open(requireNotNull(path)).use { oracle ->
            EngineBranchWorker().use { worker ->
                val root = worker.createBattle(definition)
                val oracleRoot = oracle.createBattle(definition)
                val first = worker.branch(root.snapshotJson, "move 1", "move 1")
                val oracleFirst = oracle.branch(oracleRoot.snapshotJson, "move 1", "move 1")
                assertEquals(175, first.p1Active.single().maxHp)
                assertEquals(132, first.p1Active.single().hp)
                assertEquals(23, first.p1Active.single().substituteHp)
                assertEquals(oracleFirst.p1Active.single().substituteHp, first.p1Active.single().substituteHp)
                val adapted = NativeBattleStateAdapter.adapt(first, template(first))
                val decoy = requireNotNull(adapted.pokemon.first().knownSubstituteHpFractionRange)
                assertEquals(23.0 / 175, decoy.minimum, 1e-12)
                assertEquals(decoy.minimum, decoy.maximum)
                val second = worker.branch(first.snapshotJson, "move 2", "move 1")
                val oracleSecond = oracle.branch(oracleFirst.snapshotJson, "move 2", "move 1")
                assertEquals(3, second.p1Active.single().substituteHp)
                assertEquals(oracleSecond.p1Active.single().substituteHp, second.p1Active.single().substituteHp)
                val third = worker.branch(second.snapshotJson, "move 2", "move 1")
                val oracleThird = oracle.branch(oracleSecond.snapshotJson, "move 2", "move 1")
                assertEquals(132, third.p1Active.single().hp)
                assertEquals(oracleThird.p1Active.single().hp, third.p1Active.single().hp)
                assertEquals(oracleThird.p1Active.single().substituteHp, third.p1Active.single().substituteHp)
                assertNull(third.p1Active.single().substituteHp)
                assertNull(NativeBattleStateAdapter.adapt(third, adapted).pokemon.first().knownSubstituteHpFractionRange)
                assertNull(root.p1Active.single().substituteHp)
            }
        }
    }

    private fun template(frame: NativeBattleFrame): BattleStateView {
        val pokemon = (frame.p1Team.map { it to BattleSide.ALLY } + frame.p2Team.map { it to BattleSide.OPPONENT })
            .map { (native, side) -> BattlePokemonStateView(UUID.fromString(native.uuid), side, native.activeSlot,
                native.species, null, 50, native.hp.toDouble() / native.maxHp, native.status.takeIf { it.isNotEmpty() },
                emptyMap(), native.moves.mapTo(HashSet()) { it.id }, native.ability, native.item, false) }
        return BattleStateView(uuid(99), BattleFormat.SINGLE, frame.turn, pokemon, BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
    }
}
