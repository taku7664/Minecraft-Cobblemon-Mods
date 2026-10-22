package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.simulation.NativeInBattleFormes
import jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRules
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalInBattleIdentityTest {
    @Test fun `registered Mega and Mega Z match their original preview and retain the battle form`() {
        MegaShowdownTestSpecies.install()
        for ((base, mega) in listOf("tyranitar" to "tyranitarmega", "charizard" to "charizardmegax", "lucario" to "lucariomegaz")) {
            assertEquals(base, NativeInBattleFormes.startingSpecies("showdown:$mega"))
            assertEquals(mega, NativeInBattleFormes.inBattleForme("showdown:$mega"))
            assertTrue(NativeInBattleFormes.samePublicIdentity(mon(mega), preview(base)))
        }
    }

    @Test fun `a separate Mega form matches a normal-form preview without merging regional forms`() {
        assertTrue(NativeInBattleFormes.samePublicIdentity(mon("charizard", "mega_x"), preview("charizard", "normal")))
        assertFalse(NativeInBattleFormes.samePublicIdentity(mon("zoroarkhisui"), preview("zoroark")))
        assertFalse(NativeInBattleFormes.samePublicIdentity(mon("raichu", "alola"), preview("raichu", "normal")))
        assertEquals("meganium", NativeInBattleFormes.startingSpecies("cobblemon:meganium"))
    }

    @Test fun `preview adds only the actual unseen slot after a Mega is revealed`() {
        MegaShowdownTestSpecies.install()
        val foe = mon("lucariomegaz")
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 3, listOf(foe), BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
            opponentTeamPreview = BattleOpponentTeamPreviewView(2, listOf(preview("lucario"),
                BattleOpponentTeamPreviewPokemonView(1, "cobblemon:breloom", null, 50))))
        val reading = requireNotNull(LocalSwitchRules.previewFoes(context))
        assertEquals(listOf("cobblemon:breloom"), reading.second.map { it.speciesId })
        assertEquals(2, reading.first.pokemon.count { it.side == BattleSide.OPPONENT })
    }

    private fun preview(species: String, form: String? = null) =
        BattleOpponentTeamPreviewPokemonView(0, "cobblemon:$species", form, 50)
    private fun mon(species: String, form: String? = null) = BattlePokemonStateView(UUID.randomUUID(), BattleSide.OPPONENT,
        0, "showdown:$species", form, 50, 1.0, null, emptyMap(), emptySet(), null, null, false)
}
