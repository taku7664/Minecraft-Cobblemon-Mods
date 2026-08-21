package jbro.cobblemon.morebattlecontent.internal.factory

import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogSelectorsTest {
    private val firstChoiceRandom = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `opponent selection avoids recent concepts and falls back when all are recent`() {
        val sets = (1..8).map { template("set_$it", FactoryPoolGroup.STARTER, 1, it) }
        fun concept(id: String, offset: Int) = FactoryTrainerConcept(
            conceptId = id,
            displayNameKey = "factory.concept.$id.name",
            descriptionKey = "factory.concept.$id.description",
            formats = setOf(FactoryBattleFormat.SINGLE),
            weight = 1,
            aiSkill = 2,
            aiSummary = "$id strategy",
            objectives = setOf(BattleStrategyObjective.PIVOTING),
            members = listOf(
                plan("ace", true, setOf(BattleTeamRole.ACE), sets[offset], sets[offset].moveIds.first(), 20, 100),
                plan("enabler", true, setOf(BattleTeamRole.SETUP_ENABLER), sets[offset + 1], sets[offset + 1].moveIds.first(), 100, 40),
                plan("cover", false, setOf(BattleTeamRole.WEAKNESS_COVER), sets[offset + 2], sets[offset + 2].moveIds.first(), 40, 60),
            ),
        )
        val selector = FactoryOpponentSelector(
            FactoryCatalog("test", listOf(concept("first", 0), concept("second", 4)), sets),
            firstChoiceRandom,
        )

        val fresh = selector.select(
            FactoryBattleFormat.SINGLE,
            FactoryLevelMode.LEVEL_50,
            round = 1,
            excludedConceptIds = setOf("first"),
        ) as FactoryOpponentSelectionResult.Selected
        val fallback = selector.select(
            FactoryBattleFormat.SINGLE,
            FactoryLevelMode.LEVEL_50,
            round = 1,
            excludedConceptIds = setOf("first", "second"),
        ) as FactoryOpponentSelectionResult.Selected

        assertEquals("second", fresh.concept.conceptId)
        assertEquals("first", fallback.concept.conceptId)
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
    fun `fresh draft overlaps the previous offer by at most three sets when the pool allows it`() {
        val sets = (1..16).map { template("starter_$it", FactoryPoolGroup.STARTER, 1, it) }
        val catalog = FactoryCatalog("test", emptyList(), sets)
        val selector = FactoryDraftSelector(catalog, firstChoiceRandom)
        val first = selector.select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        val second = selector.select(
            FactoryLevelMode.LEVEL_50,
            round = 1,
            rentAndTradeCount = 1,
            previousSetIds = first.sets.mapTo(linkedSetOf(), FactoryRentalSet::setId),
        )!!

        assertTrue(first.sets.map(FactoryRentalSet::setId).toSet().intersect(second.sets.map(FactoryRentalSet::setId).toSet()).size <= 3)
    }

    @Test
    fun `draft rolls one move per slot one item candidate and one of all natures exactly once`() {
        val templates = (1..6).map { index ->
            template("starter_$index", FactoryPoolGroup.STARTER, 1, index).let { fixed ->
                FactoryRentalTemplate(
                    setId = fixed.setId,
                    poolGroup = fixed.poolGroup,
                    variant = fixed.variant,
                    speciesId = fixed.speciesId,
                    moveSlots = (1..4).map { slot ->
                        listOf("cobblemon:move_${index}_${slot}_a", "cobblemon:move_${index}_${slot}_b")
                    },
                    abilityId = fixed.abilityId,
                    heldItemIds = listOf("cobblemon:item_${index}_a", "cobblemon:item_${index}_b"),
                    natureIds = FactoryNaturePool.ALL,
                    evs = fixed.evs,
                )
            }
        }
        val chooseSecond = object : FactoryCatalogRandom {
            override fun nextLong(bound: Long): Long = 0
            override fun nextInt(bound: Int): Int = if (bound > 1) 1 else 0
        }

        val draft = FactoryDraftSelector(FactoryCatalog("test", emptyList(), templates), chooseSecond)
            .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        draft.sets.forEach { set ->
            val index = set.setId.substringAfterLast('_')
            assertEquals((1..4).map { "cobblemon:move_${index}_${it}_b" }, set.moveIds)
            assertEquals("cobblemon:item_${index}_a", set.heldItemId)
            assertEquals(FactoryNaturePool.ALL[1], set.natureId)
        }
    }

    @Test
    fun `draft backtracks across item candidates to preserve the item clause`() {
        val templates = (1..6).map { index ->
            FactoryRentalTemplate(
                setId = "starter_$index",
                poolGroup = FactoryPoolGroup.STARTER,
                variant = 1,
                speciesId = "cobblemon:species$index",
                moveSlots = listOf(listOf("cobblemon:move$index")),
                abilityId = "cobblemon:ability$index",
                heldItemIds = listOf("cobblemon:shared_item", "cobblemon:item$index"),
                natureIds = FactoryNaturePool.ALL,
                evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
            )
        }

        val draft = FactoryDraftSelector(FactoryCatalog("test", emptyList(), templates), firstChoiceRandom)
            .select(FactoryLevelMode.LEVEL_50, round = 1, rentAndTradeCount = 1)!!

        assertEquals(6, draft.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
    }

    @Test
    fun `opponent selection materializes ace relationships into the ai strategy`() {
        val ace = template("garchomp", FactoryPoolGroup.ADVANCED, 1, 1, "cobblemon:swords_dance")
        val enabler = template("klefki", FactoryPoolGroup.ADVANCED, 1, 2, "cobblemon:reflect")
        val cover = template("scizor", FactoryPoolGroup.ADVANCED, 1, 3, "cobblemon:bullet_punch")
        val plans = listOf(
            plan("ace", true, setOf(BattleTeamRole.ACE), ace, "cobblemon:swords_dance", 20, 100),
            plan("enabler", true, setOf(BattleTeamRole.SETUP_ENABLER), enabler, "cobblemon:reflect", 100, 30),
            plan("cover", false, setOf(BattleTeamRole.WEAKNESS_COVER), cover, "cobblemon:bullet_punch", 10, 60),
        )
        val concept = FactoryTrainerConcept(
            conceptId = "garchomp_breakthrough",
            displayNameKey = "factory.concept.garchomp.name",
            descriptionKey = "factory.concept.garchomp.description",
            formats = setOf(FactoryBattleFormat.SINGLE),
            weight = 1,
            aiSkill = 3,
            aiSummary = "Klefki enables Garchomp while Scizor covers Ice and Fairy pressure.",
            objectives = setOf(BattleStrategyObjective.SETUP_SWEEP, BattleStrategyObjective.PRESERVE_CORE),
            members = plans,
        )
        val catalog = FactoryCatalog("test", listOf(concept), listOf(ace, enabler, cover))

        val selected = FactoryOpponentSelector(catalog, firstChoiceRandom)
            .select(FactoryBattleFormat.SINGLE, FactoryLevelMode.OPEN_LEVEL, round = 1)
            as FactoryOpponentSelectionResult.Selected

        assertEquals(3, selected.team.size)
        assertEquals(3, selected.strategy.members.size)
        assertEquals("cobblemon:garchomp1", selected.strategy.members.single { BattleTeamRole.ACE in it.roles }.speciesId)
        assertEquals(100, selected.strategy.members.single { BattleTeamRole.ACE in it.roles }.preservationPriority)
        assertTrue("cobblemon:reflect" in selected.strategy.members.single { BattleTeamRole.SETUP_ENABLER in it.roles }.preferredMoveIds)
    }

    @Test
    fun `opponent strategy only prefers moves that were actually rolled`() {
        val ace = FactoryRentalTemplate(
            setId = "ace",
            poolGroup = FactoryPoolGroup.ADVANCED,
            variant = 1,
            speciesId = "cobblemon:garchomp",
            moveSlots = listOf(
                listOf("cobblemon:protect", "cobblemon:swords_dance"),
                listOf("cobblemon:earthquake"),
                listOf("cobblemon:dragon_claw"),
                listOf("cobblemon:rock_slide"),
            ),
            abilityId = "cobblemon:rough_skin",
            heldItemIds = listOf("cobblemon:lum_berry"),
            natureIds = FactoryNaturePool.ALL,
            evs = FactoryStatSpread(0, 252, 0, 0, 4, 252),
        )
        val enabler = template("enabler", FactoryPoolGroup.ADVANCED, 1, 2)
        val cover = template("cover", FactoryPoolGroup.ADVANCED, 1, 3)
        val concept = FactoryTrainerConcept(
            conceptId = "rolled_move_strategy",
            displayNameKey = "factory.concept.rolled.name",
            descriptionKey = "factory.concept.rolled.description",
            formats = setOf(FactoryBattleFormat.SINGLE),
            weight = 1,
            aiSkill = 3,
            aiSummary = "Only prefer a setup move when it was rolled.",
            objectives = setOf(BattleStrategyObjective.SETUP_SWEEP),
            members = listOf(
                plan("ace", true, setOf(BattleTeamRole.ACE), ace, "cobblemon:swords_dance", 20, 100),
                plan("enabler", true, setOf(BattleTeamRole.SETUP_ENABLER), enabler, enabler.moveIds.first(), 100, 30),
                plan("cover", false, setOf(BattleTeamRole.WEAKNESS_COVER), cover, cover.moveIds.first(), 10, 60),
            ),
        )

        val selected = FactoryOpponentSelector(FactoryCatalog("test", listOf(concept), listOf(ace, enabler, cover)), firstChoiceRandom)
            .select(FactoryBattleFormat.SINGLE, FactoryLevelMode.OPEN_LEVEL, round = 1)
            as FactoryOpponentSelectionResult.Selected

        assertEquals(emptySet<String>(), selected.strategy.members.single { BattleTeamRole.ACE in it.roles }.preferredMoveIds)
        assertTrue("cobblemon:protect" in selected.team.single { it.setId == "ace" }.moveIds)
    }

    @Test
    fun `opponent selection can use later legal sets from a member pool`() {
        val firstAce = template("ace_first", FactoryPoolGroup.ADVANCED, 1, 1)
        val laterAce = template("ace_later", FactoryPoolGroup.ADVANCED, 1, 2)
        val enabler = template("enabler", FactoryPoolGroup.ADVANCED, 1, 3)
        val cover = template("cover", FactoryPoolGroup.ADVANCED, 1, 4)
        val concept = FactoryTrainerConcept(
            conceptId = "variable_ace",
            displayNameKey = "factory.concept.variable_ace.name",
            descriptionKey = "factory.concept.variable_ace.description",
            formats = setOf(FactoryBattleFormat.SINGLE),
            weight = 1,
            aiSkill = 3,
            aiSummary = "Use either legal ace set behind the same enabler.",
            objectives = setOf(BattleStrategyObjective.SETUP_SWEEP),
            members = listOf(
                FactoryConceptMemberPlan(
                    planId = "ace",
                    required = true,
                    roles = setOf(BattleTeamRole.ACE),
                    tacticalSummary = "Either ace variant can finish.",
                    preferredMoveIds = emptySet(),
                    leadPriority = 20,
                    preservationPriority = 100,
                    setIds = listOf(firstAce.setId, laterAce.setId),
                ),
                plan("enabler", true, setOf(BattleTeamRole.SETUP_ENABLER), enabler, enabler.moveIds.first(), 100, 30),
                plan("cover", false, setOf(BattleTeamRole.WEAKNESS_COVER), cover, cover.moveIds.first(), 10, 60),
            ),
        )
        val catalog = FactoryCatalog("test", listOf(concept), listOf(firstAce, laterAce, enabler, cover))

        val selected = FactoryOpponentSelector(catalog, firstChoiceRandom)
            .select(FactoryBattleFormat.SINGLE, FactoryLevelMode.OPEN_LEVEL, round = 1)
            as FactoryOpponentSelectionResult.Selected

        assertEquals(laterAce.setId, selected.team.single { it.speciesId == laterAce.speciesId }.setId)
    }

    private fun plan(
        id: String,
        required: Boolean,
        roles: Set<BattleTeamRole>,
        template: FactoryRentalTemplate,
        preferredMove: String,
        leadPriority: Int,
        preservationPriority: Int,
    ) = FactoryConceptMemberPlan(
        planId = id,
        required = required,
        roles = roles,
        tacticalSummary = "$id tactical purpose",
        preferredMoveIds = setOf(preferredMove),
        leadPriority = leadPriority,
        preservationPriority = preservationPriority,
        setIds = listOf(template.setId),
    )

    private fun template(
        id: String,
        group: FactoryPoolGroup,
        variant: Int,
        index: Int,
        preferredMove: String = "cobblemon:tackle",
    ) = FactoryRentalTemplate(
        setId = id,
        poolGroup = group,
        variant = variant,
        speciesId = "cobblemon:${id.filter(Char::isLetterOrDigit)}$index",
        moveIds = listOf(preferredMove),
        abilityId = "cobblemon:ability$index",
        heldItemId = "cobblemon:item$index",
        natureId = "cobblemon:hardy",
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
