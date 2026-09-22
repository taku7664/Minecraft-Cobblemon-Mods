package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicSwitchEntryHazardCalculatorTest {
    @Test
    fun `runtime switch facts calculate stealth rock and spikes from the public field`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000401")
        val state = state(
            benchId = benchId,
            benchTypes = setOf("fire", "flying"),
            conditions = listOf(
                BattleTimedEffectView("cobblemon:stealthrock", null),
                BattleTimedEffectView("cobblemon:spikes", null, stacks = 3),
            ),
        )

        val calculated = PublicBattleTacticalCalculator.calculate(context(state, switch(benchId)))

        assertEquals(0.5, requireNotNull(calculated.candidates.single().facts?.switchEntryHpLossFraction), 1e-9)
    }

    @Test
    fun `grounded target takes layered spikes while boots prevent public hazard damage`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000402")
        val hazards = listOf(BattleTimedEffectView("spikes", null, stacks = 3))
        val grounded = PublicBattleTacticalCalculator.calculate(
            context(state(benchId, setOf("normal"), hazards), switch(benchId)),
        )
        val boots = PublicBattleTacticalCalculator.calculate(
            context(state(benchId, setOf("normal"), hazards, item = "cobblemon:heavydutyboots"), switch(benchId)),
        )

        assertEquals(0.25, requireNotNull(grounded.candidates.single().facts?.switchEntryHpLossFraction), 1e-9)
        assertEquals(0.0, requireNotNull(boots.candidates.single().facts?.switchEntryHpLossFraction), 1e-9)
    }

    @Test
    fun `neutralizing gas suppresses magic guard against entry hazards`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000404")
        val rocks = listOf(BattleTimedEffectView("stealthrock", null))
        val protected = PublicBattleTacticalCalculator.calculate(
            context(state(benchId, setOf("normal"), rocks, ability = "magicguard"), switch(benchId)),
        )
        val suppressed = PublicBattleTacticalCalculator.calculate(
            context(
                state(benchId, setOf("normal"), rocks, ability = "magicguard", neutralizingGas = true),
                switch(benchId),
            ),
        )

        assertEquals(0.0, requireNotNull(protected.candidates.single().facts?.switchEntryHpLossFraction), 1e-9)
        assertEquals(0.125, requireNotNull(suppressed.candidates.single().facts?.switchEntryHpLossFraction), 1e-9)
    }

    private fun context(state: BattleStateView, candidate: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.fromString("00000000-0000-0000-0000-000000000499"),
        state = state,
        candidates = listOf(candidate),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun switch(benchId: UUID) = BattleActionCandidate(
        actionId = "switch",
        kind = BattleActionKind.SWITCH,
        actorSlot = 0,
        switchPokemonId = benchId,
    )

    private fun state(
        benchId: UUID,
        benchTypes: Set<String>,
        conditions: List<BattleTimedEffectView>,
        item: String? = null,
        ability: String? = null,
        neutralizingGas: Boolean = false,
    ): BattleStateView {
        val allyId = UUID.fromString("00000000-0000-0000-0000-000000000400")
        val opponentId = UUID.fromString("00000000-0000-0000-0000-000000000403")
        fun pokemon(
            id: UUID,
            side: BattleSide,
            activeSlot: Int?,
            types: Set<String>,
            heldItem: String? = null,
            knownAbility: String? = null,
        ) = BattlePokemonStateView(
            battlePokemonId = id,
            side = side,
            activeSlot = activeSlot,
            speciesId = "test:$id",
            formId = null,
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = emptySet(),
            knownAbilityId = knownAbility,
            knownHeldItemId = heldItem,
            fainted = false,
            knownTypeIds = types,
            combatStats = null,
        )
        return BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000498"),
            format = if (neutralizingGas) BattleFormat.DOUBLE else BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(
                pokemon(allyId, BattleSide.ALLY, 0, setOf("water")),
                pokemon(benchId, BattleSide.ALLY, null, benchTypes, item, ability),
                pokemon(opponentId, BattleSide.OPPONENT, 0, setOf("rock")),
            ) + listOfNotNull(
                pokemon(
                    UUID.fromString("00000000-0000-0000-0000-000000000405"),
                    BattleSide.OPPONENT,
                    1,
                    setOf("poison"),
                    knownAbility = "neutralizinggas",
                ).takeIf { neutralizingGas },
            ),
            field = BattleFieldStateView(
                weather = null,
                terrain = null,
                roomEffects = emptyList(),
                globalEffects = emptyList(),
                sideConditions = mapOf(BattleSide.ALLY to conditions, BattleSide.OPPONENT to emptyList()),
            ),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
    }
}
