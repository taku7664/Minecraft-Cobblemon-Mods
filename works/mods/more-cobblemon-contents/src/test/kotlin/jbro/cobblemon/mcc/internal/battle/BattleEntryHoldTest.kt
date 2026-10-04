package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BattleEntryHoldTest {
    private fun classify(
        opponents: List<ActorType>,
        players: Int = 1,
        side: Int = 1,
        stage: String? = null,
        tagged: Boolean = false,
        labels: Set<String> = emptySet(),
    ) = BattleEntryHold.classify(players, side, opponents, stage, tagged, labels)

    @Test
    fun `wild, legendary and trainer battles are held`() {
        assertEquals(BattleEntryStyle.WILD, classify(listOf(ActorType.WILD)))
        assertEquals(BattleEntryStyle.WILD, classify(listOf(ActorType.WILD), labels = setOf("alpha")))
        assertEquals(BattleEntryStyle.LEGENDARY, classify(listOf(ActorType.WILD), labels = setOf("legendary")))
        assertEquals(BattleEntryStyle.LEGENDARY, classify(listOf(ActorType.WILD), labels = setOf("ultra_beast")))
        assertEquals(BattleEntryStyle.TRAINER, classify(listOf(ActorType.NPC)))
    }

    @Test
    fun `MCC's wild trainers are held but its other content is not`() {
        assertEquals(BattleEntryStyle.TRAINER, classify(listOf(ActorType.NPC), stage = "wild_trainer", tagged = true))
        assertEquals(BattleEntryStyle.TRAINER, classify(listOf(ActorType.NPC), stage = "wild_trainer_ace", tagged = true))
        assertNull(classify(listOf(ActorType.NPC), stage = "gym", tagged = true))
        assertNull(classify(listOf(ActorType.NPC), stage = null, tagged = true))
    }

    @Test
    fun `player battles, multi battles and mixed opponents start at once`() {
        assertNull(classify(listOf(ActorType.PLAYER)))
        assertNull(classify(listOf(ActorType.WILD), players = 2))
        assertNull(classify(listOf(ActorType.WILD), side = 2))
        assertNull(classify(listOf(ActorType.WILD, ActorType.NPC)))
        assertNull(classify(emptyList()))
    }
}
