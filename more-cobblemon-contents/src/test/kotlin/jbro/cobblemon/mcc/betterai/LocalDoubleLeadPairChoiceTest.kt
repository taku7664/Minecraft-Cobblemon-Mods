package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalDoubleLeadPairChoiceTest {
    @Test
    fun `doubles jointly covers two public opposing lead slots instead of duplicating one matchup`() {
        val pairs = (0L until 200L).map { seed -> requireNotNull(LocalLeadChoice.choose(context(seed))).leads }
        assertTrue(pairs.count { GRASS in it } > 150,
            "One water and one grass cover fire and water together: ${pairs.groupingBy { it.toSet() }.eachCount()}")
        assertTrue(pairs.all { it.size == 2 && it.distinct().size == 2 })
    }

    @Test
    fun `equivalent ordered pair slots are drawn together without a first slot bias`() {
        val pairs = (0L until 200L).map { seed -> requireNotNull(LocalLeadChoice.choose(context(seed))).leads }
        val firstSlotGrass = pairs.count { it.first() == GRASS }
        assertTrue(firstSlotGrass in 70..130, "Both assignments of the same pair have equal public support: $firstSlotGrass")
        assertEquals(requireNotNull(LocalLeadChoice.choose(context(8))).leads,
            requireNotNull(LocalLeadChoice.choose(context(8))).leads)
    }

    @Test
    fun `a fainted member is excluded and same species members retain different slot identities`() {
        val alive = listOf(own(WATER_ZERO, "water"), own(WATER_ONE, "water"))
        val fainted = own(GRASS, "grass", fainted = true)
        val choice = requireNotNull(LocalLeadChoice.choose(context(7, alive + fainted)))
        assertEquals(setOf(WATER_ZERO, WATER_ONE), choice.leads.toSet())
        assertEquals(2, choice.leads.size)
    }

    @Test
    fun `known own rain entry and swimmer cooperate while an active weather suppressor prevents it`() {
        val team = listOf(own(WATER_ZERO, "water"), own(WATER_ONE, "water"), own(GRASS, "water"))
        fun selectedPair(abilities: Map<UUID, String>) = (0L until 200L).count { seed ->
            val choice = requireNotNull(LocalLeadChoice.choose(context(seed, team, abilities, rainPreview())))
            choice.leads.toSet() == setOf(WATER_ZERO, WATER_ONE)
        }
        val baseline = selectedPair(emptyMap())
        val rainSwimmer = selectedPair(mapOf(WATER_ZERO to "drizzle", WATER_ONE to "swiftswim"))
        val suppressed = selectedPair(mapOf(WATER_ZERO to "drizzle", WATER_ONE to "airlock"))
        assertTrue(rainSwimmer > baseline + 30, "Own exact ability cooperation must change choices: $baseline -> $rainSwimmer")
        assertTrue(suppressed < baseline, "Air Lock suppresses its paired rain setter: $suppressed versus $baseline")
    }

    @Test
    fun `a partial exact own build view keeps public pair evaluation available`() {
        val full = context(7)
        val partial = BattleLeadChoiceContext(full.format, full.ownTeam, full.ownMoves,
            BattleExactOwnTeamView(full.exactOwnTeam.builds.take(2)), full.opponentTeamPreview,
            full.trainerProfile, seed = full.seed)
        val choice = assertDoesNotThrow<LocalLeadChoice.Choice?> { LocalLeadChoice.choose(partial) }
        assertNotNull(choice)
        assertEquals(2, requireNotNull(choice).leads.distinct().size)
    }

    private fun context(seed: Long, team: List<BattlePokemonStateView> = listOf(
        own(WATER_ZERO, "water"), own(WATER_ONE, "water"), own(GRASS, "grass")),
        abilities: Map<UUID, String> = emptyMap(), preview: BattleOpponentTeamPreviewView = coveragePreview()) = BattleLeadChoiceContext(
        BattleFormat.DOUBLE, team,
        BattlePublicActionCatalogView(team.map { own -> BattlePokemonActionCatalogView(own.battlePokemonId, listOf(
            BattlePublicMoveOptionView("${own.knownTypeIds.single()}move", BattleMoveCandidateView(
                own.knownTypeIds.single(), BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 10),
                BattlePublicMoveKnowledge.EXACT_OWN)), moveSetComplete = true) }),
        BattleExactOwnTeamView(team.map { own -> BattleExactPokemonBuildView(own.battlePokemonId,
            abilities[own.battlePokemonId] ?: "none", null,
            "serious", "N", EVS, EVS.mapValues { 31 }, null, own.speciesId) }),
        preview, BattleTrainerProfile.boss(), seed = seed,
    )

    private fun coveragePreview() = BattleOpponentTeamPreviewView(2, listOf("fire", "water").mapIndexed { slot, type ->
            BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:$type", null, 50, setOf(type))
        })

    private fun rainPreview() = BattleOpponentTeamPreviewView(2, (0..1).map { slot ->
        BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:normal", null, 50, setOf("normal"),
            BattleCombatStatRangesView(BattleIntegerRange(149, 151), BattleIntegerRange(99, 101),
                BattleIntegerRange(99, 101), BattleIntegerRange(99, 101), BattleIntegerRange(99, 101),
                BattleIntegerRange(149, 151), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
    })

    private fun own(id: UUID, type: String, fainted: Boolean = false) = BattlePokemonStateView(id,
        BattleSide.ALLY, null, "cobblemon:$type", null, 50, if (fainted) 0.0 else 1.0, null, emptyMap(),
        emptySet(), null, null, fainted, setOf(type), BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100))

    private companion object {
        val WATER_ZERO = UUID(0, 1)
        val WATER_ONE = UUID(0, 2)
        val GRASS = UUID(0, 3)
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
