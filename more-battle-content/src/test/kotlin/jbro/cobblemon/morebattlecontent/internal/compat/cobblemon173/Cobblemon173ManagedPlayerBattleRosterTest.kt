package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Cobblemon173ManagedPlayerBattleRosterTest {
    @Test
    fun `attaches every managed player battle team to one temporary roster owned by that player`() {
        val playerId = UUID.randomUUID()
        val placements = ArrayList<Triple<UUID, Int, String>>()

        val rosterOwner = Cobblemon173ManagedPlayerBattleRoster.attach(
            playerId = playerId,
            team = listOf("lead", "second", "third"),
            createRoster = { it },
            setMember = { owner, slot, member -> placements += Triple(owner, slot, member) },
        )

        assertEquals(playerId, rosterOwner)
        assertEquals(
            listOf(
                Triple(playerId, 0, "lead"),
                Triple(playerId, 1, "second"),
                Triple(playerId, 2, "third"),
            ),
            placements,
        )
    }

    @Test
    fun `managed participant creates its actor only after the temporary roster is complete`() {
        val playerId = UUID.randomUUID()
        val events = ArrayList<String>()

        val participant = Cobblemon173ManagedPlayerBattleParticipants.prepare(
            playerId = playerId,
            team = listOf("lead", "second"),
            createRoster = {
                events += "roster"
                "roster-$it"
            },
            setMember = { _, slot, member -> events += "slot-$slot-$member" },
            createActor = { actorPlayerId, actorTeam ->
                events += "actor"
                "actor-$actorPlayerId-${actorTeam.joinToString()}"
            },
        )

        assertEquals(listOf("roster", "slot-0-lead", "slot-1-second", "actor"), events)
        assertEquals("roster-$playerId", participant.roster)
        assertEquals("actor-$playerId-lead, second", participant.actor)
    }
}
