package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.morebattlecontent.internal.tower.TowerStreakStage
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
            TowerStreakStage.INTRODUCTORY,
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
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedProfileIds = setOf("first"),
        ) as TowerOpponentSelectionResult.Selected
        val fallback = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedProfileIds = setOf("first", "second"),
        ) as TowerOpponentSelectionResult.Selected

        assertEquals("second", fresh.profile.profileId)
        assertEquals("first", fallback.profile.profileId)
    }

    @Test
    fun `avoids recently seen species before allowing a repeated team`() {
        val sets = validSets()
        val profile = profile("rotating", 1, TowerBattleFormat.SINGLE, sets.map(TowerPokemonSet::setId))
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val fresh = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedSpeciesIds = sets.take(3).mapTo(LinkedHashSet(), TowerPokemonSet::speciesId),
        ) as TowerOpponentSelectionResult.Selected
        val exhausted = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedSpeciesIds = sets.mapTo(LinkedHashSet(), TowerPokemonSet::speciesId),
        ) as TowerOpponentSelectionResult.Selected

        assertTrue(fresh.team.none { it.speciesId in sets.take(3).map(TowerPokemonSet::speciesId) })
        assertEquals(3, exhausted.team.size)
    }

    @Test
    fun `double selection skips alternate species and duplicate held items`() {
        val sets = validSets().toMutableList()
        sets[1] = pokemonSet(2, speciesId = sets[0].speciesId)
        sets[2] = pokemonSet(3, heldItemId = sets[0].heldItemId)
        val profile = profile("double", 1, TowerBattleFormat.DOUBLE, sets.map(TowerPokemonSet::setId))
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val result = selector.select(
            TowerStreakStage.INTRODUCTORY,
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
            TowerStreakStage.INTRODUCTORY,
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
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
        )

        assertEquals(TowerOpponentSelectionResult.NoLegalTeam("invalid"), result)
    }

    @Test
    fun `legendary class off removes specials while on can still select a normal only team`() {
        val normalSets = validSets()
        val specials = listOf(
            pokemonSet(7, speciesId = "cobblemon:phione", mechanic = MajorBattleMechanic.MEGA),
            pokemonSet(8, speciesId = "cobblemon:mewtwo", mechanic = MajorBattleMechanic.MEGA),
        )
        val allSets = normalSets + specials
        val profile = profile(
            "mixed",
            1,
            TowerBattleFormat.SINGLE,
            allSets.map(TowerPokemonSet::setId),
            stageIds = listOf(TowerStreakStage.INTRODUCTORY, TowerStreakStage.PRO),
        )
        val selector = TowerOpponentSelector(catalog(listOf(profile), allSets), FixedRandom())

        val disabled = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            legendaryClassAllowed = false,
        ) as TowerOpponentSelectionResult.Selected
        val enabled = selector.select(
            TowerStreakStage.PRO,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            legendaryClassAllowed = true,
        ) as TowerOpponentSelectionResult.Selected

        assertTrue(disabled.team.none { TowerLegendaryClassPolicy.isLegendaryClass(it.speciesId) })
        assertTrue(enabled.team.none { TowerLegendaryClassPolicy.isLegendaryClass(it.speciesId) })
        assertEquals(3, enabled.team.size)
    }

    @Test
    fun `legendary class on includes specials in the eligible pool`() {
        val allSets = listOf(
            pokemonSet(1),
            pokemonSet(2),
            pokemonSet(7, speciesId = "cobblemon:phione", mechanic = MajorBattleMechanic.MEGA),
        )
        val profile = profile("mixed", 1, TowerBattleFormat.SINGLE, allSets.map(TowerPokemonSet::setId))
        val selector = TowerOpponentSelector(catalog(listOf(profile), allSets), FixedRandom())

        val enabled = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            legendaryClassAllowed = true,
        ) as TowerOpponentSelectionResult.Selected

        assertEquals(3, enabled.team.size)
        assertEquals(1, enabled.team.count { TowerLegendaryClassPolicy.isLegendaryClass(it.speciesId) })
    }

    @Test
    fun `keeps the signature species when every fresh set would strip the anchor`() {
        val sets = validSets()
        val signatureSpecies = sets.first().speciesId
        val profile = profile(
            "signature",
            1,
            TowerBattleFormat.SINGLE,
            sets.map(TowerPokemonSet::setId),
            signatureSpeciesIds = listOf(signatureSpecies),
        )
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val result = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedSpeciesIds = setOf(signatureSpecies),
        )

        result as TowerOpponentSelectionResult.Selected
        assertEquals(3, result.team.size)
        assertTrue(result.team.any { it.speciesId == signatureSpecies })
    }

    @Test
    fun `keeps the team style when every fresh set would strip the anchor`() {
        val styledSet = pokemonSet(1).let { base ->
            TowerPokemonSet(
                setId = base.setId,
                setTier = base.setTier,
                mechanic = base.mechanic,
                speciesId = base.speciesId,
                formId = null,
                abilityId = null,
                natureId = base.natureId,
                heldItemId = base.heldItemId,
                moves = listOf("cobblemon:swordsdance"),
                ivs = base.ivs,
                evs = base.evs,
            )
        }
        val sets = listOf(styledSet) + (2..6).map(::pokemonSet)
        val profile = profile(
            "styled",
            1,
            TowerBattleFormat.SINGLE,
            sets.map(TowerPokemonSet::setId),
            teamStyle = TowerTrainerStyle.SETUP_SWEEP,
        )
        val selector = TowerOpponentSelector(catalog(listOf(profile), sets), FixedRandom())

        val result = selector.select(
            TowerStreakStage.INTRODUCTORY,
            TowerBattleFormat.SINGLE,
            TowerOpponentKind.REGULAR,
            MajorBattleMechanic.MEGA,
            excludedSpeciesIds = setOf(styledSet.speciesId),
        )

        result as TowerOpponentSelectionResult.Selected
        assertEquals(3, result.team.size)
        assertTrue(result.team.any(TowerTrainerStyle.SETUP_SWEEP::matches))
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
        stageIds: List<TowerStreakStage> = listOf(TowerStreakStage.INTRODUCTORY),
        teamStyle: TowerTrainerStyle = TowerTrainerStyle.BALANCED,
        signatureSpeciesIds: List<String> = emptyList(),
    ) = TowerOpponentProfile(
        profileId = id,
        displayNameKey = "trainer.test.$id",
        stageIds = stageIds,
        format = format,
        opponentKind = TowerOpponentKind.REGULAR,
        mechanic = mechanic,
        weight = weight,
        aiSkill = 1,
        theme = "balanced",
        setIds = setIds,
        teamStyle = teamStyle,
        signatureSpeciesIds = signatureSpeciesIds,
    )

    private fun validSets() = (1..6).map(::pokemonSet)

    private fun pokemonSet(
        index: Int,
        speciesId: String = "cobblemon:species_$index",
        heldItemId: String? = "minecraft:item_$index",
        mechanic: MajorBattleMechanic? = null,
    ) = TowerPokemonSet(
        setId = "set_$index",
            setTier = 1,
            mechanic = mechanic,
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
