package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.LocalFieldEffectProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFieldEffectProjectorTest {
    @Test
    fun `sticky web is projected once onto the opposing side`() {
        val initial = state()
        val web = sideCondition("cobblemon:stickyweb")

        val afterFirst = LocalFieldEffectProjector.apply(initial, BattleSide.ALLY, web)
        val afterSecond = LocalFieldEffectProjector.apply(afterFirst, BattleSide.ALLY, web)

        assertEquals(
            BattleTimedEffectView("cobblemon:stickyweb", null, stacks = 1),
            afterFirst.field.sideConditions.getValue(BattleSide.OPPONENT).single(),
        )
        assertSame(afterFirst, afterSecond, "A saturated one-layer hazard must be a projected no-op")
    }

    @Test
    fun `observer one layer hazard without a stack count is already saturated`() {
        val observed = state(
            field = BattleFieldStateView(
                weather = null,
                terrain = null,
                roomEffects = emptyList(),
                globalEffects = emptyList(),
                sideConditions = BattleSide.entries.associateWith { side ->
                    if (side == BattleSide.OPPONENT) {
                        listOf(BattleTimedEffectView("cobblemon:stealthrock", null, stacks = null))
                    } else {
                        emptyList()
                    }
                },
            ),
        )

        val projected = LocalFieldEffectProjector.apply(
            observed,
            BattleSide.ALLY,
            sideCondition("cobblemon:stealthrock"),
        )

        assertSame(observed, projected)
    }

    @Test
    fun `spikes and toxic spikes stack only to their public caps`() {
        fun applyRepeatedly(effectId: String, times: Int): BattleStateView {
            var projected = state()
            repeat(times) {
                projected = LocalFieldEffectProjector.apply(
                    projected,
                    BattleSide.ALLY,
                    sideCondition(effectId),
                )
            }
            return projected
        }

        val spikes = applyRepeatedly("cobblemon:spikes", 5)
        val toxicSpikes = applyRepeatedly("cobblemon:toxicspikes", 4)

        assertEquals(3, spikes.field.sideConditions.getValue(BattleSide.OPPONENT).single().stacks)
        assertEquals(2, toxicSpikes.field.sideConditions.getValue(BattleSide.OPPONENT).single().stacks)
    }

    @Test
    fun `declared lower stack cap is respected when stricter than the known hazard cap`() {
        val limitedSpikes = BattleMoveEffectView(
            kind = BattleMoveEffectKind.SIDE_CONDITION,
            target = BattleMoveEffectTarget.TARGET_SIDE,
            probability = 1.0,
            valueId = "spikes",
            amountRange = BattleIntegerRange(1, 2),
        )
        val once = LocalFieldEffectProjector.apply(state(), BattleSide.ALLY, limitedSpikes)
        val twice = LocalFieldEffectProjector.apply(once, BattleSide.ALLY, limitedSpikes)
        val thrice = LocalFieldEffectProjector.apply(twice, BattleSide.ALLY, limitedSpikes)

        assertEquals(2, thrice.field.sideConditions.getValue(BattleSide.OPPONENT).single().stacks)
        assertSame(twice, thrice)
    }

    @Test
    fun `weather terrain and room effects enter the recursive field state`() {
        val actor = pokemon(item = null)
        val initial = state(pokemon = listOf(actor))
        val rain = fieldEffect(BattleMoveEffectKind.WEATHER, "raindance")
        val terrain = fieldEffect(BattleMoveEffectKind.TERRAIN, "electricterrain")
        val trickRoom = fieldEffect(BattleMoveEffectKind.FIELD_CONDITION, "trickroom")

        val afterRain = LocalFieldEffectProjector.apply(initial, BattleSide.ALLY, rain, ACTOR_ID)
        val afterTerrain = LocalFieldEffectProjector.apply(afterRain, BattleSide.ALLY, terrain, ACTOR_ID)
        val afterRoom = LocalFieldEffectProjector.apply(afterTerrain, BattleSide.ALLY, trickRoom, ACTOR_ID)

        assertEquals(BattleTimedEffectView("raindance", 5), afterRoom.field.weather)
        assertEquals(BattleTimedEffectView("electricterrain", 5), afterRoom.field.terrain)
        assertEquals("trickroom", afterRoom.field.roomEffects.single().effectId)
    }

    @Test
    fun `known duration extender is projected while an unrevealed opposing item stays ranged`() {
        val lightClayState = state(pokemon = listOf(pokemon(item = "cobblemon:light_clay")))
        val reflect = sideCondition("cobblemon:reflect", BattleMoveEffectTarget.USER_SIDE)

        val ownScreen = LocalFieldEffectProjector.apply(lightClayState, BattleSide.ALLY, reflect, ACTOR_ID)
        assertEquals(8, ownScreen.field.sideConditions.getValue(BattleSide.ALLY).single().remainingTurns)

        val opponentId = UUID.fromString("00000000-0000-0000-0000-000000000503")
        val unknownItemState = state(pokemon = listOf(pokemon(opponentId, BattleSide.OPPONENT, null)))
        val uncertainScreen = LocalFieldEffectProjector.apply(
            unknownItemState,
            BattleSide.OPPONENT,
            reflect,
            opponentId,
        )
        assertEquals(
            BattleIntegerRange(5, 8),
            uncertainScreen.field.sideConditions.getValue(BattleSide.OPPONENT).single().remainingTurnsRange,
        )
    }

    @Test
    fun `using an active room move toggles that room off`() {
        val trickRoom = fieldEffect(BattleMoveEffectKind.FIELD_CONDITION, "trickroom")
        val initial = state(
            pokemon = listOf(pokemon()),
            field = BattleFieldStateView(
                weather = null,
                terrain = null,
                roomEffects = listOf(BattleTimedEffectView("trickroom", 3)),
                globalEffects = emptyList(),
                sideConditions = BattleSide.entries.associateWith { emptyList() },
            ),
        )

        val toggled = LocalFieldEffectProjector.apply(initial, BattleSide.ALLY, trickRoom, ACTOR_ID)

        assertTrue(toggled.field.roomEffects.isEmpty())
    }

    private fun sideCondition(
        effectId: String,
        target: BattleMoveEffectTarget = BattleMoveEffectTarget.TARGET_SIDE,
    ) = BattleMoveEffectView(
        kind = BattleMoveEffectKind.SIDE_CONDITION,
        target = target,
        probability = 1.0,
        valueId = effectId,
    )

    private fun fieldEffect(kind: BattleMoveEffectKind, effectId: String) = BattleMoveEffectView(
        kind = kind,
        target = BattleMoveEffectTarget.FIELD,
        probability = 1.0,
        valueId = effectId,
    )

    private fun pokemon(
        id: UUID = ACTOR_ID,
        side: BattleSide = BattleSide.ALLY,
        item: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = item,
        fainted = false,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun state(
        field: BattleFieldStateView = BattleFieldStateView.empty(),
        pokemon: List<BattlePokemonStateView> = emptyList(),
    ) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000501"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = pokemon,
        field = field,
        remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000502")
    }
}
