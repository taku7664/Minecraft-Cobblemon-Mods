package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalSwitchEntryProjectionTest {
    @Test
    fun `entry weather and terrain abilities replace the public field`() {
        assertEquals("raindance", switchIn("drizzle").field.weather?.effectId)
        assertEquals("sunnyday", switchIn("drought").field.weather?.effectId)
        assertEquals("electricterrain", switchIn("electricsurge").field.terrain?.effectId)
        assertEquals("psychicterrain", switchIn("psychicsurge").field.terrain?.effectId)
    }

    @Test
    fun `entry field abilities honor extenders and cannot replace strong weather`() {
        assertEquals(8, switchIn("drizzle", item = "damprock").field.weather?.remainingTurns)
        assertEquals(8, switchIn("electricsurge", item = "terrainextender").field.terrain?.remainingTurns)
        assertEquals(
            "primordialsea",
            switchIn("drought", weather = "primordialsea").field.weather?.effectId,
        )
    }

    @Test
    fun `sticky web lowers a grounded entrant but boots ignore it`() {
        val web = BattleTimedEffectView("stickyweb", null, stacks = 1)
        assertEquals(-1, switchIn(null, hazards = listOf(web)).incoming().statStages["speed"])
        assertNull(switchIn(null, item = "heavydutyboots", hazards = listOf(web)).incoming().statStages["speed"])
    }

    @Test
    fun `toxic spikes poison entrants and a grounded poison type absorbs them`() {
        val spikes = BattleTimedEffectView("toxicspikes", null, stacks = 2)
        assertEquals("tox", switchIn(null, hazards = listOf(spikes)).incoming().statusId)
        val absorbed = switchIn(null, types = setOf("poison"), hazards = listOf(spikes))
        assertNull(absorbed.incoming().statusId)
        assertEquals(emptyList<BattleTimedEffectView>(), absorbed.field.sideConditions.getValue(BattleSide.ALLY))
    }

    @Test
    fun `poison type absorbs toxic spikes even while wearing heavy duty boots`() {
        val spikes = BattleTimedEffectView("toxicspikes", null, stacks = 2)
        val projected = switchIn(
            null,
            item = "heavydutyboots",
            types = setOf("poison"),
            hazards = listOf(spikes),
        )

        assertEquals(emptyList<BattleTimedEffectView>(), projected.field.sideConditions.getValue(BattleSide.ALLY))
    }

    @Test
    fun `misty terrain and safeguard prevent toxic spikes status`() {
        val spikes = BattleTimedEffectView("toxicspikes", null, stacks = 2)
        assertNull(switchIn(null, hazards = listOf(spikes), terrain = "mistyterrain").incoming().statusId)
        assertNull(
            switchIn(
                null,
                hazards = listOf(spikes, BattleTimedEffectView("safeguard", 3)),
            ).incoming().statusId,
        )
    }

    @Test
    fun `clear amulet blocks sticky web and mirror armor reflects it`() {
        val web = BattleTimedEffectView("stickyweb", null, stacks = 1)
        assertNull(switchIn(null, item = "clearamulet", hazards = listOf(web)).incoming().statStages["speed"])
        val reflected = switchIn("mirrorarmor", hazards = listOf(web))
        assertNull(reflected.incoming().statStages["speed"])
        assertEquals(
            -1,
            reflected.pokemon.single { it.side == BattleSide.OPPONENT }.statStages["speed"],
        )
    }

    @Test
    fun `neutralizing gas suppresses sticky web response abilities`() {
        val web = BattleTimedEffectView("stickyweb", null, stacks = 1)

        assertEquals(1, switchIn("contrary", hazards = listOf(web)).incoming().statStages["speed"])
        assertEquals(
            -1,
            switchIn("contrary", hazards = listOf(web), neutralizingGas = true).incoming().statStages["speed"],
        )
    }

    private fun switchIn(
        ability: String?,
        item: String? = null,
        types: Set<String> = setOf("normal"),
        hazards: List<BattleTimedEffectView> = emptyList(),
        terrain: String? = null,
        weather: String? = null,
        neutralizingGas: Boolean = false,
    ): BattleStateView {
        val active = mon(UUID.randomUUID(), BattleSide.ALLY, 0, null, null, setOf("normal"))
        val incoming = mon(INCOMING, BattleSide.ALLY, null, ability, item, types)
        val opponent = mon(UUID.randomUUID(), BattleSide.OPPONENT, 0, null, null, setOf("normal"))
        val gas = mon(UUID.randomUUID(), BattleSide.OPPONENT, 1, "neutralizinggas", null, setOf("poison"))
        val state = BattleStateView(
            UUID.randomUUID(), if (neutralizingGas) BattleFormat.DOUBLE else BattleFormat.SINGLE, 1,
            listOf(active, incoming, opponent) + listOfNotNull(gas.takeIf { neutralizingGas }),
            BattleFieldStateView(
                weather?.let { BattleTimedEffectView(it, 3) },
                terrain?.let { BattleTimedEffectView(it, 3) }, emptyList(), emptyList(),
                mapOf(BattleSide.ALLY to hazards, BattleSide.OPPONENT to emptyList()),
            ),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList(),
        )
        return LocalSwitchStateProjector.project(
            state, BattleSide.ALLY,
            BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = INCOMING),
        )
    }

    private fun BattleStateView.incoming() = pokemon.single { it.battlePokemonId == INCOMING }

    private fun mon(
        id: UUID,
        side: BattleSide,
        slot: Int?,
        ability: String?,
        item: String?,
        types: Set<String>,
    ) =
        BattlePokemonStateView(
            id, side, slot, "probe", null, 50, 1.0, null, emptyMap(), emptySet(), ability, item,
            false, types, if (side == BattleSide.ALLY) {
                BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100)
            } else {
                BattleCombatStatRangesView(
                    BattleIntegerRange(140, 160), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                    BattleIntegerRange(90, 110), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                    BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
                )
            },
        )

    private companion object {
        val INCOMING: UUID = UUID.fromString("00000000-0000-0000-0000-000000009001")
    }
}
