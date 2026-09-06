package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class EmbeddedPublicResourceTest {
    private val ally = "p1a: 00000000-0000-0000-0000-000000000103"
    private val opponent = "p2a: 00000000-0000-0000-0000-000000000203"

    private fun context(vararg lines: String, retainSources: Boolean = false) =
        javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            val input = JsonParser.parseReader(it).asJsonObject
            val log = input.getAsJsonArray("publicLog")
            if (!retainSources) {
                for (index in log.size() - 1 downTo 0) {
                    val line = log[index].asString
                    if (line.contains("[from] item: ") || line.contains("[from] ability: ")) log.remove(index)
                }
            }
            lines.forEach(log::add)
            EmbeddedTeamInput.context(input, UUID(0, 41), 5, 0)
        }

    private fun holder(vararg lines: String) = context(*lines).state.pokemon.single {
        it.side == BattleSide.OPPONENT && it.activeSlot == 0
    }.also { assertEquals("taurospaldeaaqua", it.speciesId) }

    @Test
    fun `recorded helmet recoil reveals the owner not the damaged attacker`() {
        val state = context(retainSources = true).state
        assertEquals("rockyhelmet", state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }.knownHeldItemId)
        assertEquals("heavydutyboots", state.pokemon.single { it.side == BattleSide.ALLY && it.activeSlot == 0 }.knownHeldItemId)
    }

    @Test
    fun `self source and explicit source reveal only the declared resource`() {
        assertEquals("leftovers", holder("|-heal|$opponent|30/100|[from] item: Leftovers").knownHeldItemId, opponent)
        assertEquals("roughskin", holder("|-damage|$ally|10/100|[from] ability: Rough Skin|[of] $opponent").knownAbilityId)
        assertNull(holder("|-damage|$opponent|10/100|[from] move: Bind").knownHeldItemId)
        assertNull(holder("|-damage|$opponent|10/100|[from] item: ").knownHeldItemId)
    }

    @Test
    fun `unknown explicit owner never falls back to damaged pokemon and removal wins`() {
        assertNull(holder("|-damage|$opponent|10/100|[from] item: Rocky Helmet|[of] p1a: unseen").knownHeldItemId)
        assertNull(holder("|-damage|$opponent|10/100|[from] item: Rocky Helmet|[of] ").knownHeldItemId)
        assertNull(holder("|-heal|$opponent|30/100|[from] item: Leftovers", "|-enditem|$opponent|Leftovers").knownHeldItemId)
    }
}
