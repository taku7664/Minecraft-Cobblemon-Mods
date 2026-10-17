package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.simulation.NativeMidBattleStateRules
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeMidBattleStateRulesTest {
    @Test
    fun `a public singles board with active Dynamax can be rebuilt`() {
        assertEquals(null, NativeMidBattleStateRules.blocker(state(BattleSide.ALLY)))
        assertEquals(null, NativeMidBattleStateRules.blocker(state(BattleSide.OPPONENT)))
    }

    private fun state(dynamaxedSide: BattleSide) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 2,
        pokemon = BattleSide.entries.mapIndexed { index, side -> BattlePokemonStateView(
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
        ) },
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )
}
