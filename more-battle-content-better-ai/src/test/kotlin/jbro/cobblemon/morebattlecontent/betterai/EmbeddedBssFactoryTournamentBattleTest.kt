package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class EmbeddedBssFactoryTournamentBattleTest {
    @Test
    fun `reconstructed entries match the tournament replay seeds`() {
        assertEquals(
            listOf("Umbreon", "Scizor", "Glimmora", "Urshifu-Rapid-Strike", "Thundurus-Therian", "Goodra-Hisui"),
            species(EmbeddedBssFactoryTournamentBattle.p1Entry()),
        )
        assertEquals(
            listOf("Rotom-Wash", "Landorus-Therian", "Chi-Yu", "Kleavor", "Glastrier", "Orthworm"),
            species(EmbeddedBssFactoryTournamentBattle.p2Entry()),
        )

        val thundurus = EmbeddedBssFactoryTournamentBattle.p1Entry().getAsJsonArray("sets")[4].asJsonObject
        assertEquals("Choice Specs", thundurus["item"].asString)
        assertEquals("Water", thundurus["teraType"].asString)
        assertEquals(listOf("Volt Switch", "Thunderbolt", "Tera Blast", "Sludge Bomb"), moves(thundurus))
    }

    @Test
    fun `battle pair uses the exact tournament selections in lead order`() {
        val pair = EmbeddedBssFactoryTournamentBattle.pair(seedIndex = 0)
        val p1 = pair.getAsJsonObject("p1")
        val p2 = pair.getAsJsonObject("p2")

        assertNotSame(p1, p2)
        assertEquals(listOf("Thundurus-Therian", "Umbreon", "Urshifu-Rapid-Strike"), species(p1))
        assertEquals(listOf("Landorus-Therian", "Rotom-Wash", "Chi-Yu"), species(p2))
    }

    private fun species(team: com.google.gson.JsonObject) = team.getAsJsonArray("sets")
        .map { it.asJsonObject["species"].asString }

    private fun moves(set: com.google.gson.JsonObject) = set.getAsJsonArray("moves").map { it.asString }
}
