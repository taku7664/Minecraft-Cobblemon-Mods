package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.simulation.NativeMidBattleStateRules
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicPersistentMoveState
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeMidBattleStateRulesTest {
    @Test
    fun `a public singles board with active Dynamax can be rebuilt`() {
        assertEquals(null, NativeMidBattleStateRules.blocker(state(BattleSide.ALLY)))
        assertEquals(null, NativeMidBattleStateRules.blocker(state(BattleSide.OPPONENT)))
    }

    @Test
    fun `Dynamax use remains public after it ends and after its user faints`() {
        val started = Cobblemon173PublicPersistentMoveState.afterVolatile(
            emptySet(), LocalDynamax.VOLATILE, true, null, null,
        )
        val ended = Cobblemon173PublicPersistentMoveState.afterVolatile(
            started, LocalDynamax.VOLATILE, false, null, null,
        )
        assertTrue(LocalDynamax.USED in ended)
        assertTrue(LocalDynamax.VOLATILE !in ended)

        BattleSide.entries.forEach { side ->
            val board = state(dynamaxedSide = null, spentOnFaintedSide = side)
            assertTrue(LocalDynamax.used(board, side))
            assertEquals(null, NativeMidBattleStateRules.blocker(board))
        }
    }

    @Test
    fun `a doubles board is rebuilt with both slots filled, and waits on an empty slot still to be filled`() {
        fun doubles(opponentActives: Int, opponentLeft: Int) = state(dynamaxedSide = BattleSide.OPPONENT).let { single ->
            val pokemon = BattleSide.entries.flatMap { side ->
                val actives = if (side == BattleSide.OPPONENT) opponentActives else 2
                (0 until actives).map { slot ->
                    single.pokemon.first { it.side == side }.let { base -> BattlePokemonStateView(
                        UUID.nameUUIDFromBytes("${side.name}$slot".toByteArray()), side, slot, base.speciesId, null, 50, 0.75,
                        null, emptyMap(), emptySet(), null, null, false, knownVolatileEffectIds = base.knownVolatileEffectIds) }
                }
            }
            BattleStateView(single.battleId, BattleFormat.DOUBLE, 3, pokemon, single.field,
                mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to opponentLeft), emptyList(), emptyList())
        }
        // Two Dynamax-marked actives a side: rebuilt (it refused every doubles board, 801 times in nine games).
        assertEquals(null, NativeMidBattleStateRules.blocker(doubles(2, 2)))
        // The last one standing fills one slot alone.
        assertEquals(null, NativeMidBattleStateRules.blocker(doubles(1, 1)))
        // One active with two left: a replacement is still to come.
        assertEquals("ACTIVE_NOT_READY:OPPONENT", NativeMidBattleStateRules.blocker(doubles(1, 2)))
    }

    private fun state(
        dynamaxedSide: BattleSide?,
        spentOnFaintedSide: BattleSide? = null,
    ) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 2,
        pokemon = BattleSide.entries.flatMapIndexed { index, side -> listOf(BattlePokemonStateView(
            battlePokemonId = UUID.fromString("00000000-0000-4000-8000-%012d".format(index + 1)),
            side = side,
            activeSlot = 0,
            speciesId = if (side == BattleSide.ALLY) "cobblemon:pikachu" else "cobblemon:eevee",
            formId = null,
            level = 50,
            hpFraction = 0.75,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = emptySet(),
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = false,
            knownVolatileEffectIds = if (side == dynamaxedSide) setOf("dynamax", "dynamaxturns:2") else emptySet(),
        )) + listOfNotNull(BattlePokemonStateView(
            battlePokemonId = UUID.fromString("00000000-0000-4000-8000-%012d".format(index + 11)),
            side = side,
            activeSlot = null,
            speciesId = "cobblemon:ditto",
            formId = null,
            level = 50,
            hpFraction = 0.0,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = emptySet(),
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = true,
            knownVolatileEffectIds = setOf(LocalDynamax.USED),
        ).takeIf { side == spentOnFaintedSide }) },
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )
}
