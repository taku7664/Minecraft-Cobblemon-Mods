package jbro.cobblemon.morebattlecontent.internal.factory

import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogSelectorsTest {
    private val firstChoiceRandom = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `opponent selection avoids recent trainers and falls back when all are recent`() {
        val sets = (1..8).map { template("set_$it", FactoryPoolGroup.STARTER, 1, it) }
        val selector = FactoryOpponentSelector(
            FactoryCatalog("test", listOf(trainer("first"), trainer("second")), sets),
            firstChoiceRandom,
        )

        val fresh = selector.select(
            FactoryBattleFormat.SINGLE,
            FactoryLevelMode.LEVEL_50,
            round = 1,
            excludedTrainerIds = setOf("first"),
        ) as FactoryOpponentSelectionResult.Selected
        val fallback = selector.select(
            FactoryBattleFormat.SINGLE,
            FactoryLevelMode.LEVEL_50,
            round = 1,
            excludedTrainerIds = setOf("first", "second"),
        ) as FactoryOpponentSelectionResult.Selected

        assertEquals("second", fresh.trainer.trainerId)
        assertEquals("first", fallback.trainer.trainerId)
    }

    @Test
    fun `draft contains exactly the original trade elevation count from the next pool`() {
        val starter = (1..6).map { template("starter_$it", FactoryPoolGroup.STARTER, 1, it) }
        val intermediate = (7..12).map { template("intermediate_$it", FactoryPoolGroup.INTERMEDIATE, 1, it) }
        val catalog = FactoryCatalog("test", emptyList(), starter + intermediate)

        val ordinary = FactoryDraftSelector(catalog, firstChoiceRandom)
            .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!
        val elevated = FactoryDraftSelector(catalog, firstChoiceRandom)
            .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 7)!!

        assertEquals(6, ordinary.sets.count { it.ivs.hp == 0 })
        assertEquals(1, elevated.sets.count { it.ivs.hp == 4 })
        assertEquals(5, elevated.sets.count { it.ivs.hp == 0 })
    }

    @Test
    fun `fresh draft avoids every recently offered species when the pool allows it`() {
        val sets = (1..16).map { template("starter_$it", FactoryPoolGroup.STARTER, 1, it) }
        val selector = FactoryDraftSelector(FactoryCatalog("test", emptyList(), sets), firstChoiceRandom)
        val first = selector.select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        val second = selector.select(
            FactoryLevelMode.LEVEL_50,
            round = 1,
            rentAndTradeCount = 1,
            recentSpeciesIds = first.sets.mapTo(linkedSetOf(), FactoryRentalSet::speciesId),
        )!!

        assertTrue(first.sets.map(FactoryRentalSet::speciesId).toSet().intersect(second.sets.map(FactoryRentalSet::speciesId).toSet()).isEmpty())
    }

    @Test
    fun `draft only chooses complete presets and never rerolls their contents`() {
        val templates = (1..6).map { template("starter_$it", FactoryPoolGroup.STARTER, 1, it) }
        val draft = FactoryDraftSelector(FactoryCatalog("test", emptyList(), templates), firstChoiceRandom)
            .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        draft.sets.forEach { rental ->
            val template = templates.single { it.setId == rental.setId }
            assertEquals(template.moveIds, rental.moveIds)
            assertEquals(template.heldItemId, rental.heldItemId)
            assertEquals(template.natureId, rental.natureId)
        }
    }

    @Test
    fun `fixed item clause backtracks to another complete preset instead of changing an item`() {
        val shared = (1..5).map { template("shared_$it", FactoryPoolGroup.STARTER, 1, it, item = "cobblemon:leftovers") }
        val legal = (6..11).map { template("legal_$it", FactoryPoolGroup.STARTER, 1, it) }
        val selector = FactoryDraftSelector(FactoryCatalog("test", emptyList(), shared + legal), firstChoiceRandom)

        val draft = selector.select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        assertEquals(6, draft.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
        assertEquals(1, draft.sets.count { it.heldItemId == "cobblemon:leftovers" })
    }

    @Test
    fun `draft fails when fixed complete presets cannot satisfy the item clause`() {
        val illegal = (1..8).map { template("shared_$it", FactoryPoolGroup.STARTER, 1, it, item = "cobblemon:leftovers") }
        assertNull(
            FactoryDraftSelector(FactoryCatalog("test", emptyList(), illegal), firstChoiceRandom)
                .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1),
        )
    }

    @Test
    fun `opponent draws a legal team from the same pool and exposes one ace strategy`() {
        val sets = (1..8).map { index ->
            template(
                "advanced_$index",
                FactoryPoolGroup.ADVANCED,
                1,
                index,
                roles = if (index % 2 == 0) setOf(BattleTeamRole.SETUP_ENABLER) else setOf(BattleTeamRole.WALLBREAKER),
            )
        }
        val selected = FactoryOpponentSelector(FactoryCatalog("test", listOf(trainer("riley")), sets), firstChoiceRandom)
            .select(FactoryBattleFormat.DOUBLE, FactoryLevelMode.OPEN_LEVEL, round = 1)
            as FactoryOpponentSelectionResult.Selected

        assertEquals(4, selected.team.size)
        assertEquals(4, selected.team.map(FactoryRentalSet::speciesId).distinct().size)
        assertEquals(4, selected.team.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
        assertEquals(1, selected.strategy.members.count { BattleTeamRole.ACE in it.roles })
        selected.team.zip(selected.strategy.members).forEach { (set, member) ->
            assertTrue(member.preferredMoveIds.all(set.moveIds::contains))
        }
    }

    private fun trainer(id: String) = FactoryTrainerProfile(
        trainerId = id,
        displayNameKey = "factory.trainer.$id.name",
        descriptionKey = "factory.trainer.shared.description",
        formats = FactoryBattleFormat.entries.toSet(),
        weight = 1,
        aiSkill = 2,
        aiSummary = "$id uses complete rental presets.",
        objectives = setOf(BattleStrategyObjective.PRESERVE_CORE),
    )

    private fun template(
        id: String,
        group: FactoryPoolGroup,
        variant: Int,
        index: Int,
        item: String = "cobblemon:item$index",
        roles: Set<BattleTeamRole> = setOf(BattleTeamRole.WEAKNESS_COVER),
    ) = FactoryRentalTemplate(
        setId = id,
        poolGroup = group,
        variant = variant,
        speciesId = "cobblemon:${id.filter(Char::isLetterOrDigit)}$index",
        moveIds = (1..4).map { "cobblemon:move${index}_$it" },
        abilityId = "cobblemon:ability$index",
        heldItemId = item,
        natureId = "cobblemon:hardy",
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
        roles = roles,
        preferredMoveIds = setOf("cobblemon:move${index}_1"),
        leadPriority = 50,
        preservationPriority = 50,
    )
}
