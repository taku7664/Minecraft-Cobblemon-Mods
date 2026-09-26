package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeShowdownRequestActionFactoryTest {
    @Test
    fun `move request creates target and mechanic variants from native legality`() {
        val frame = frame(
            p1Request = """
                {
                  "active": [
                    {
                      "moves": [{"move":"Tackle","id":"tackle","pp":35,"maxpp":35,"target":"normal","disabled":false}],
                      "canMegaEvo":true,
                      "canDynamax":true,
                      "canTerastallize":"Electric"
                    },
                    {
                      "moves": [{"move":"Splash","id":"splash","pp":40,"maxpp":40,"target":"self","disabled":false}]
                    }
                  ]
                }
            """.trimIndent(),
        )

        val choices = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
            .map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) }
            .toSet()

        assertEquals(8, choices.size)
        assertTrue("move 1 1, move 1" in choices)
        assertTrue("move 1 2 mega, move 1" in choices)
        assertTrue("move 1 1 dynamax, move 1" in choices)
        assertTrue("move 1 2 terastallize, move 1" in choices)
    }

    @Test
    fun `disabled native moves never become candidates`() {
        val frame = singleFrame(
            """
            {
              "active": [{
                "moves": [
                  {"move":"Tackle","id":"tackle","pp":35,"maxpp":35,"target":"normal","disabled":true},
                  {"move":"Quick Attack","id":"quickattack","pp":30,"maxpp":30,"target":"normal","disabled":false}
                ]
              }]
            }
            """.trimIndent(),
        )

        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)

        assertEquals(listOf("quickattack"), actions.mapNotNull { it.moveId }.distinct())
        assertEquals(listOf(1), actions.mapNotNull { it.moveSlot }.distinct())
    }

    @Test
    fun `forced double switches keep slots and reject duplicate replacements`() {
        val faintedLeft = pokemon(ALLY_LEFT, 0, 0, "tackle")
        val faintedRight = pokemon(ALLY_RIGHT, 1, 0, "splash")
        val benchOne = pokemon(ALLY_BENCH_ONE, null, 100, "tackle")
        val benchTwo = pokemon(ALLY_BENCH_TWO, null, 100, "tackle")
        val frame = frame(
            p1Request = """{"forceSwitch":[true,true]}""",
            p1Active = listOf(faintedLeft, faintedRight),
            p1Team = listOf(faintedLeft, faintedRight, benchOne, benchTwo),
        )

        val choices = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
            .map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) }
            .toSet()

        assertEquals(setOf("switch 3, switch 4", "switch 4, switch 3"), choices)
        assertEquals(choices, NativeShowdownRequestActionFactory.actions(
            BattleSide.ALLY, frame, maxVoluntarySwitchTargetsPerSlot = 1,
        ).map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) }.toSet())
    }

    @Test
    fun `future singles switch limit retains moves and selects one viable bench target`() {
        val active = pokemon(ALLY_LEFT, 0, 100, "tackle")
        val weak = pokemon(ALLY_BENCH_ONE, null, 80, "tackle").copy(types = listOf("Fire"))
        val resistant = pokemon(ALLY_BENCH_TWO, null, 70, "tackle").copy(types = listOf("Water"))
        val opponent = pokemon(OPPONENT_LEFT, 0, 100, "splash").copy(types = listOf("Water"))
        val frame = singleFrame("""{"active":[{"moves":[{"id":"tackle","target":"normal"}]}]}""").copy(
            p1Active = listOf(active), p1Team = listOf(active, weak, resistant),
            p2Active = listOf(opponent), p2Team = listOf(opponent),
        )

        val full = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)
        val limited = NativeShowdownRequestActionFactory.actions(
            BattleSide.ALLY, frame, maxVoluntarySwitchTargetsPerSlot = 1,
        )

        assertEquals(2, full.count { it.kind == BattleActionKind.SWITCH })
        assertEquals(1, limited.count { it.kind == BattleActionKind.USE_MOVE })
        assertEquals(ALLY_BENCH_TWO, limited.single { it.kind == BattleActionKind.SWITCH }.switchPokemonId)
    }

    @Test
    fun `future doubles switch limit reserves distinct targets by active slot`() {
        val left = pokemon(ALLY_LEFT, 0, 100, "tackle")
        val right = pokemon(ALLY_RIGHT, 1, 100, "splash")
        val frame = frame(
            p1Request = """{"active":[{"moves":[{"id":"tackle","target":"normal"}]},{"moves":[{"id":"splash","target":"self"}]}]}""",
            p1Active = listOf(left, right),
            p1Team = listOf(left, right, pokemon(ALLY_BENCH_ONE, null, 100, "tackle"),
                pokemon(ALLY_BENCH_TWO, null, 100, "tackle")),
        )

        val choices = NativeShowdownRequestActionFactory.actions(
            BattleSide.ALLY, frame, maxVoluntarySwitchTargetsPerSlot = 1,
        ).map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, frame) }.toSet()

        assertTrue("switch 3, switch 4" in choices)
        assertEquals(6, choices.size)
    }

    @Test
    fun `native wait request stays a whole-side wait`() {
        val frame = singleFrame("""{"wait":true}""")

        val actions = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame)

        assertEquals(1, actions.size)
        assertEquals(BattleActionKind.WAIT, actions.single().kind)
        assertEquals(null, actions.single().actorSlot)
        assertEquals("pass", NativeShowdownChoiceEncoder.encode(actions.single(), BattleSide.ALLY, frame))
    }

    private fun singleFrame(request: String): NativeBattleFrame {
        val ally = pokemon(ALLY_LEFT, 0, 100, "tackle", "quickattack")
        val opponent = pokemon(OPPONENT_LEFT, 0, 100, "splash")
        return NativeBattleFrame(
            snapshotJson = "{}",
            turn = 1,
            requestState = "move",
            ended = false,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = request,
            p2RequestJson = "{\"wait\":true}",
            log = emptyList(),
        )
    }

    private fun frame(
        p1Request: String,
        p1Active: List<NativePokemonFrame> = listOf(
            pokemon(ALLY_LEFT, 0, 100, "tackle"),
            pokemon(ALLY_RIGHT, 1, 100, "splash"),
        ),
        p1Team: List<NativePokemonFrame> = p1Active,
    ): NativeBattleFrame {
        val opponents = listOf(
            pokemon(OPPONENT_LEFT, 0, 100, "splash"),
            pokemon(OPPONENT_RIGHT, 1, 100, "splash"),
        )
        return NativeBattleFrame(
            snapshotJson = "{}",
            turn = 1,
            requestState = "move",
            ended = false,
            p1Active = p1Active,
            p2Active = opponents,
            p1Team = p1Team,
            p2Team = opponents,
            p1RequestJson = p1Request,
            p2RequestJson = "{\"wait\":true}",
            log = emptyList(),
        )
    }

    private fun pokemon(uuid: UUID, activeSlot: Int?, hp: Int, vararg moves: String) = NativePokemonFrame(
        uuid = uuid.toString(),
        species = "mew",
        hp = hp,
        maxHp = 100,
        status = "",
        ability = "synchronize",
        item = "",
        types = listOf("Psychic"),
        boosts = emptyMap(),
        volatiles = emptyList(),
        moves = moves.map { NativeMoveFrame(it, 10, 10, false) },
        activeSlot = activeSlot,
    )

    private companion object {
        val ALLY_LEFT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val ALLY_RIGHT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val ALLY_BENCH_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000103")
        val ALLY_BENCH_TWO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000104")
        val OPPONENT_LEFT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val OPPONENT_RIGHT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
    }
}
