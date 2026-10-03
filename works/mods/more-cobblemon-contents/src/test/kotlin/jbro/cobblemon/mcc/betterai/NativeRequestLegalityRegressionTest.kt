package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Request grammar only: no evaluation scores or selection weights participate in these checks. */
class NativeRequestLegalityRegressionTest {
    @Test
    fun `one forced replacement preserves the partner pass in either position`() {
        for (forcedSlot in 0..1) {
            val active = listOf(pokemon(1, 0), pokemon(2, 1))
            val request = if (forcedSlot == 0) """{"forceSwitch":[true,false]}"""
                else """{"forceSwitch":[false,true]}"""
            val frame = frame(request, active, active + pokemon(3, null))
            assertEquals(setOf(if (forcedSlot == 0) "switch 3, pass" else "pass, switch 3"), choices(frame))
        }
    }

    @Test
    fun `one bench replacement for two fainted slots permits either placement but never two passes`() {
        val active = listOf(pokemon(1, 0, hp = 0), pokemon(2, 1, hp = 0))
        val frame = frame("""{"forceSwitch":[true,true]}""", active, active + pokemon(3, null))
        assertEquals(setOf("switch 3, pass", "pass, switch 3"), choices(frame))
    }

    @Test
    fun `fainted and commanding slots submit pass during a move request`() {
        for (inactive in listOf(pokemon(1, 0, hp = 0), pokemon(1, 0).copy(volatiles = listOf("commanding")))) {
            val active = listOf(inactive, pokemon(2, 1))
            val frame = frame(doubleRequest(), active)
            assertEquals(setOf("pass, move 1 1", "pass, move 1 2"), choices(frame))
        }
    }

    @Test
    fun `surviving double slots keep explicit target locations when both partners have fainted`() {
        val allies = listOf(pokemon(1, 0), pokemon(2, 1, hp = 0))
        val opponents = listOf(pokemon(11, 0), pokemon(12, 1, hp = 0))
        val frame = frame(doubleRequest(), allies).copy(p2Active = opponents, p2Team = opponents)
        assertEquals(setOf("move 1 1, pass"), choices(frame))
    }

    @Test
    fun `revival replacement includes only fainted targets including a fainted active partner`() {
        val active = listOf(pokemon(1, 0), pokemon(2, 1, hp = 0))
        val request = """{"forceSwitch":[true,false],"side":{"pokemon":[{"uuid":"${id(1)}","reviving":true}]}}"""
        val frame = frame(request, active, active + listOf(pokemon(3, null), pokemon(4, null, hp = 0)))
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
        assertEquals(setOf("switch 2, pass", "switch 4, pass"), choices(frame))
        assertTrue(actions.all { "revival_blessing" in it.componentActions.first().tags })
    }

    @Test
    fun `locked move in a later original move slot uses the single request slot`() {
        val actor = pokemon(1, 0, moves = listOf("tackle", "outrage"))
        val frame = frame("""{"active":[{"moves":[{"id":"outrage","target":"normal"}],"trapped":true}]}""",
            listOf(actor)).copy(p2Active = listOf(pokemon(11, 0)), p2Team = listOf(pokemon(11, 0)))
        assertEquals(setOf("move 1"), choices(frame))
    }

    @Test
    fun `recharge and struggle are valid request moves absent from the normal moveset`() {
        for (moveId in listOf("recharge", "struggle")) {
            val frame = frame("""{"active":[{"moves":[{"id":"$moveId","target":"self"}],"trapped":true}]}""",
                listOf(pokemon(1, 0)))
            assertEquals(setOf("move 1"), choices(frame))
        }
    }

    @Test
    fun `active dynamax uses max guard target despite a disabled base status move`() {
        val actor = pokemon(1, 0, moves = listOf("thunderwave")).copy(volatiles = listOf("dynamax"))
        val request = """{"active":[{"moves":[{"id":"thunderwave","target":"normal","disabled":true}],"canDynamax":false,"maxMoves":{"maxMoves":[{"move":"maxguard","target":"self"}]}}]}"""
        val frame = frame(request, listOf(actor))
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
        assertEquals(setOf("move 1"), choices(frame))
        assertTrue(actions.all { it.mechanic == null && it.targets.isEmpty() })
    }

    @Test
    fun `pollen puff preserves its legal allied target in normal target requests`() {
        val active = listOf(pokemon(1, 0, moves = listOf("pollenpuff")), pokemon(2, 1, moves = listOf("splash")))
        val request = """{"active":[{"moves":[{"id":"pollenpuff","target":"normal"}]},{"moves":[{"id":"splash","target":"self"}]}]}"""
        val frame = frame(request, active)
        assertEquals(setOf("move 1 -2, move 1", "move 1 1, move 1", "move 1 2, move 1"), choices(frame))
    }

    @Test
    fun `ordinary allied attacks remain excluded by the live product policy`() {
        val frame = frame(doubleRequest(), listOf(pokemon(1, 0), pokemon(2, 1)))
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
        assertTrue(actions.all { action -> action.componentActions.filter { it.kind == BattleActionKind.USE_MOVE }
            .all { move -> move.targets.none { it.side == BattleSide.ALLY } } })
    }

    @Test
    fun `opponent ordinary attacks keep foe targets but exclude their own partner`() {
        val frame = frame(doubleRequest(), listOf(pokemon(1, 0), pokemon(2, 1)))
            .copy(p2RequestJson = doubleRequest())
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame)
        assertEquals(4, actions.size)
        assertTrue(actions.all { action -> action.componentActions.all { move ->
            move.targets.singleOrNull()?.side == BattleSide.ALLY
        } })
    }

    @Test
    fun `opponent allied status targets remain available under the same friendly target policy`() {
        val opponents = listOf(pokemon(11, 0, moves = listOf("skillswap")),
            pokemon(12, 1, moves = listOf("splash")))
        val request = """{"active":[{"moves":[{"id":"skillswap","target":"normal","category":"Status"}]},{"moves":[{"id":"splash","target":"self","category":"Status"}]}]}"""
        val frame = frame(doubleRequest(), listOf(pokemon(1, 0), pokemon(2, 1)))
            .copy(p2RequestJson = request, p2Active = opponents, p2Team = opponents)
        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame)
        val choices = actions.map { NativeShowdownChoiceEncoder.encode(it, BattleSide.OPPONENT, frame) }.toSet()
        assertEquals(setOf("move 1 -2, move 1", "move 1 1, move 1", "move 1 2, move 1"), choices)
    }

    @Test
    fun `double requests keep target locations when empty partners are absent from the frame`() {
        val request = """{"active":[{"moves":[{"id":"tackle","target":"normal"}]},null]}"""
        val foes = listOf(pokemon(11, 0))
        val frame = frame(request, listOf(pokemon(1, 0)))
            .copy(p2RequestJson = request, p2Active = foes, p2Team = foes)
        assertEquals(setOf("move 1 1, pass"), choices(frame))
    }

    private fun choices(frame: NativeBattleFrame): Set<String> =
        NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
            .map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) }.toSet()

    private fun doubleRequest() = """{"active":[{"moves":[{"id":"tackle","target":"normal"}]},{"moves":[{"id":"tackle","target":"normal"}]}]}"""

    private fun frame(request: String, active: List<NativePokemonFrame>, team: List<NativePokemonFrame> = active): NativeBattleFrame {
        val opponents = listOf(pokemon(11, 0), pokemon(12, 1))
        return NativeBattleFrame(snapshotJson = "{}", turn = 1, requestState = "move", ended = false,
            p1Active = active, p2Active = opponents, p1Team = team, p2Team = opponents,
            p1RequestJson = request, p2RequestJson = """{"wait":true}""", log = emptyList())
    }

    private fun pokemon(number: Int, slot: Int?, hp: Int = 100, moves: List<String> = listOf("tackle")) =
        NativePokemonFrame(uuid = id(number).toString(), species = "mew", hp = hp, maxHp = 100,
            status = "", ability = "synchronize", item = "", types = listOf("Psychic"), boosts = emptyMap(),
            volatiles = emptyList(), moves = moves.map { NativeMoveFrame(it, 10, 10, false) }, activeSlot = slot)

    private fun id(number: Int): UUID = UUID(0L, number.toLong())
}
