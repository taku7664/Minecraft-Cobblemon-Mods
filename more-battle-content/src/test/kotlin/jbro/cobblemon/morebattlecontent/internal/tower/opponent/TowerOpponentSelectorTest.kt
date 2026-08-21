package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentSelectorTest {
    @Test
    fun `selects weighted profile and a legal single team`() {
        val first = profile("first", 1, TowerBattleFormat.SINGLE, (1..6).map { "set_$it" })
        val second = profile("second", 3, TowerBattleFormat.SINGLE, (1..6).map { "set_$it" })
        val selector = TowerOpponentSelector(catalog(listOf(first, second), validSets()), FixedRandom(longValue = 1))

        val result = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
        )

        result as TowerOpponentSelectionResult.Selected
        assertEquals("second", result.profile.profileId)
        assertEquals(3, result.team.size)
        assertEquals(3, result.team.map(TowerPokemonSet::speciesId).distinct().size)
        assertEquals(3, result.team.mapNotNull(TowerPokemonSet::heldItemId).distinct().size)
    }

    @Test
    fun `avoids recent profiles while alternatives exist and falls back when all are recent`() {
        val first = profile("first", 1, TowerBattleFormat.SINGLE, (1..6).map { "set_$it" })
        val second = profile("second", 1, TowerBattleFormat.SINGLE, (1..6).map { "set_$it" })
        val selector = TowerOpponentSelector(catalog(listOf(first, second), validSets()), FixedRandom())

        val fresh = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedProfileIds = setOf("first"),
        ) as TowerOpponentSelectionResult.Selected
        val fallback = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedProfileIds = setOf("first", "second"),
        ) as TowerOpponentSelectionResult.Selected

        assertEquals("second", fresh.profile.profileId)
        assertEquals("first", fallback.profile.profileId)
    }

    @Test
    fun `double selection skips alternate species and duplicate held items`() {
        val sets = validSets().toMutableList()
        sets[1] = pokemonSet(2, speciesId = sets[0].speciesId)
        sets[2] = pokemonSet(3, heldItemId = sets[0].heldItemId)
        val profile = profile("double", 1, TowerBattleFormat.DOUBLE, sets.map(TowerPokemonSet::setId))
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val result = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.DOUBLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
        )

        result as TowerOpponentSelectionResult.Selected
        assertEquals(4, result.team.size)
        assertEquals(4, result.team.map(TowerPokemonSet::speciesId).distinct().size)
        assertEquals(4, result.team.mapNotNull(TowerPokemonSet::heldItemId).distinct().size)
    }

    @Test
    fun `reports missing profile without borrowing another format opponent kind or mechanic`() {
        val profile = profile(
            "single",
            1,
            TowerBattleFormat.SINGLE,
            (1..6).map { "set_$it" },
            MajorBattleMechanic.DYNAMAX,
        )
        val selector = TowerOpponentSelector(catalog(listOf(profile), validSets()), FixedRandom())

        val result = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
        )

        assertEquals(TowerOpponentSelectionResult.NoEligibleProfile, result)
    }

    @Test
    fun `fails closed if an invalid programmatic catalog has no legal team`() {
        val sets = (1..6).map { pokemonSet(it, speciesId = "cobblemon:one_species") }
        val profile = profile("invalid", 1, TowerBattleFormat.SINGLE, sets.map(TowerPokemonSet::setId))
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val result = selector.select(
            TowerRank.RANK_1,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
        )

        assertEquals(TowerOpponentSelectionResult.NoLegalTeam("invalid"), result)
    }

    private fun catalog(
        profiles: List<TowerOpponentProfile>,
        sets: List<TowerPokemonSet>,
    ) = TowerOpponentCatalog("test", profiles, sets)

    private fun profile(
        id: String,
        weight: Int,
        format: TowerBattleFormat,
        setIds: List<String>,
        mechanic: MajorBattleMechanic = MajorBattleMechanic.MEGA,
    ) = TowerOpponentProfile(
        profileId = id,
        displayNameKey = "trainer.test.$id",
        rankIds = listOf(TowerRank.RANK_1),
        format = format,
        opponentKind = TowerOpponentKind.REGULAR,
        mechanic = mechanic,
        weight = weight,
        aiSkill = 1,
        theme = "balanced",
        setIds = setIds,
    )

    private fun validSets() = (1..6).map(::pokemonSet)

    private fun pokemonSet(
        index: Int,
        speciesId: String = "cobblemon:species_$index",
        heldItemId: String? = "minecraft:item_$index",
    ) = TowerPokemonSet(
        setId = "set_$index",
        setTier = 1,
        speciesId = speciesId,
        formId = null,
        abilityId = null,
        natureId = "cobblemon:hardy",
        heldItemId = heldItemId,
        moves = listOf("cobblemon:move_$index"),
        ivs = TowerStatSpread(15, 15, 15, 15, 15, 15),
        evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
    )

    private class FixedRandom(
        private val longValue: Long = 0,
        private val intValue: Int = 0,
    ) : TowerOpponentRandom {
        override fun nextLong(bound: Long): Long {
            assertTrue(longValue in 0 until bound)
            return longValue
        }

        override fun nextInt(bound: Int): Int {
            assertTrue(intValue in 0 until bound)
            return intValue
        }
    }
}
