package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalLeadChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLeadChoiceTest {
    @Test
    fun `an introductory trainer keeps the team order`() {
        assertNull(LocalLeadChoice.choose(context(BattleTrainerProfile.balanced(0), seed = 1L)))
    }

    @Test
    fun `against a fire team the water Pokemon scores best and usually leads`() {
        val choice = requireNotNull(LocalLeadChoice.choose(context(BattleTrainerProfile.boss(), seed = 1L)))
        assertEquals(WATER, choice.scores.maxBy { it.value }.key, "${choice.scores}")
        assertTrue(choice.scores.getValue(GRASS) < choice.scores.getValue(FIRE_MON), "${choice.scores}")

        val leads = leadCounts(BattleTrainerProfile.boss())
        assertTrue(leads.getValue(WATER) > 150, "$leads")
    }

    @Test
    fun `a standard trainer varies its lead more than a boss`() {
        val boss = leadCounts(BattleTrainerProfile.boss()).getValue(WATER)
        val standard = leadCounts(BattleTrainerProfile.balanced(2)).getValue(WATER)
        assertTrue(standard < boss, "standard=$standard boss=$boss")
        assertTrue(standard > 67, "The best matchup still leads more than a third of the time: $standard")
    }

    @Test
    fun `doubles picks two different leads`() {
        val choice = requireNotNull(LocalLeadChoice.choose(context(BattleTrainerProfile.boss(), seed = 7L, format = BattleFormat.DOUBLE)))
        assertEquals(2, choice.leads.distinct().size)
    }

    private fun leadCounts(profile: BattleTrainerProfile): Map<UUID, Int> =
        (0L until 200L).map { seed -> requireNotNull(LocalLeadChoice.choose(context(profile, seed))).leads.single() }
            .groupingBy { it }.eachCount().withDefault { 0 }

    private fun context(profile: BattleTrainerProfile, seed: Long, format: BattleFormat = BattleFormat.SINGLE) =
        BattleLeadChoiceContext(
            format = format,
            ownTeam = listOf(own(GRASS, "grass"), own(FIRE_MON, "fire"), own(WATER, "water")),
            ownMoves = BattlePublicActionCatalogView(listOf(
                moves(GRASS, "grass"), moves(FIRE_MON, "fire"), moves(WATER, "water"),
            )),
            exactOwnTeam = BattleExactOwnTeamView(listOf(GRASS, FIRE_MON, WATER).map { id ->
                BattleExactPokemonBuildView(id, "none", null, "serious", "N", EVS, EVS.mapValues { 31 }, "normal", "probe")
            }),
            opponentTeamPreview = BattleOpponentTeamPreviewView(3, (0 until 6).map { slot ->
                BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:probe$slot", null, 50, setOf("fire"),
                    previewStats())
            }),
            trainerProfile = profile,
            seed = seed,
        )

    private fun previewStats() = BattleCombatStatRangesView(
        maxHp = BattleIntegerRange(140, 160), attack = BattleIntegerRange(90, 110),
        defence = BattleIntegerRange(90, 110), specialAttack = BattleIntegerRange(90, 110),
        specialDefence = BattleIntegerRange(90, 110), speed = BattleIntegerRange(90, 110),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )

    private fun own(id: UUID, type: String) = BattlePokemonStateView(
        id, BattleSide.ALLY, null, "cobblemon:$type", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false,
        setOf(type), BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100),
        knownVolatileEffectIds = emptySet(),
    )

    private fun moves(id: UUID, type: String) = BattlePokemonActionCatalogView(id, listOf(
        BattlePublicMoveOptionView("${type}move", BattleMoveCandidateView(type, BattleMoveDamageCategory.SPECIAL, 90.0,
            100.0, 0, 10), BattlePublicMoveKnowledge.EXACT_OWN),
    ), moveSetComplete = true)

    private companion object {
        val GRASS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000b01")
        val FIRE_MON: UUID = UUID.fromString("00000000-0000-0000-0000-000000000b02")
        val WATER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000b03")
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
