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

    private fun switchIn(
        ability: String?,
        item: String? = null,
        types: Set<String> = setOf("normal"),
        hazards: List<BattleTimedEffectView> = emptyList(),
    ): BattleStateView {
        val active = mon(UUID.randomUUID(), 0, null, null, setOf("normal"))
        val incoming = mon(INCOMING, null, ability, item, types)
        val state = BattleStateView(
            UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(active, incoming),
            BattleFieldStateView(
                null, null, emptyList(), emptyList(),
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

    private fun mon(id: UUID, slot: Int?, ability: String?, item: String?, types: Set<String>) =
        BattlePokemonStateView(
            id, BattleSide.ALLY, slot, "probe", null, 50, 1.0, null, emptyMap(), emptySet(), ability, item,
            false, types, BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100),
        )

    private companion object {
        val INCOMING: UUID = UUID.fromString("00000000-0000-0000-0000-000000009001")
    }
}
