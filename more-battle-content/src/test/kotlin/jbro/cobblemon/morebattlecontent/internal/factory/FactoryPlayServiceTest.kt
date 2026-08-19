package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryPlayServiceTest {
    private val playerId = UUID.randomUUID()
    private val battleId = UUID.randomUUID()
    private val random = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `command friendly flow creates draft locks rentals and starts a catalog opponent battle`() {
        val catalog = catalog()
        var launched: FactoryBattleLaunchRequest? = null
        val service = playService(catalog) { request ->
            launched = request
            FactoryBattleLaunchResult.Started(battleId)
        }

        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted
        assertEquals(FactoryPlayPhase.INITIAL_DRAFT, initial.view.phase)
        assertEquals(6, initial.view.draftSets.size)

        val selected = service.selectDraft(playerId, initial.view.draftSets.take(3).map(FactoryRentalSet::setId))
            as FactoryPlayResult.Accepted
        assertEquals(FactoryPlayPhase.READY, selected.view.phase)
        assertTrue(selected.view.canReviseSelection)
        assertEquals(initial.view.draftSets.take(3).map(FactoryRentalSet::setId), selected.view.teamSets.map(FactoryRentalSet::setId))

        val active = service.beginBattle(playerId) as FactoryPlayResult.Accepted
        assertEquals(FactoryPlayPhase.IN_BATTLE, active.view.phase)
        assertTrue(!active.view.canReviseSelection)
        assertEquals(selected.view.teamSets.map(FactoryRentalSet::setId), active.view.teamSets.map(FactoryRentalSet::setId))
        assertEquals(battleId, active.view.activeBattleId)
        assertNotNull(launched)
        val request = checkNotNull(launched)
        assertEquals(FactoryLevelMode.LEVEL_50, request.levelMode)
        assertEquals(1, request.battleNumber)
        assertEquals(1, request.strategyBrief.members.count { BattleTeamRole.ACE in it.roles })
    }

    @Test
    fun `battle start applies the exact server validated player order`() {
        var launched: FactoryBattleLaunchRequest? = null
        val service = playService(catalog()) { request ->
            launched = request
            FactoryBattleLaunchResult.Started(battleId)
        }
        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted
        val selectedIds = initial.view.draftSets.take(3).map(FactoryRentalSet::setId)
        service.selectDraft(playerId, selectedIds)
        val requestedOrder = listOf(selectedIds[2], selectedIds[0], selectedIds[1])

        val active = service.beginBattle(playerId, requestedOrder) as FactoryPlayResult.Accepted

        assertEquals(requestedOrder, checkNotNull(launched).playerTeam.sets.map(FactoryRentalSet::setId))
        assertEquals(requestedOrder, active.view.teamSets.map(FactoryRentalSet::setId))
    }

    @Test
    fun `battle start rejects missing duplicate or foreign order without launching`() {
        var launchCount = 0
        val service = playService(catalog()) {
            launchCount++
            FactoryBattleLaunchResult.Started(battleId)
        }
        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted
        val selectedIds = initial.view.draftSets.take(3).map(FactoryRentalSet::setId)
        service.selectDraft(playerId, selectedIds)

        listOf(
            selectedIds.take(2),
            listOf(selectedIds[0], selectedIds[0], selectedIds[2]),
            listOf(selectedIds[0], selectedIds[1], "foreign"),
        ).forEach { invalidOrder ->
            val rejected = service.beginBattle(playerId, invalidOrder) as FactoryPlayResult.Rejected
            assertEquals(FactoryPlayError.INVALID_SELECTION, rejected.error)
        }

        assertEquals(0, launchCount)
        assertEquals(FactoryPlayPhase.READY, service.status(playerId).phase)
        assertEquals(selectedIds, service.status(playerId).teamSets.map(FactoryRentalSet::setId))
    }

    @Test
    fun `displayed draft numbers select rentals in their visible order`() {
        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted

        val selected = service.selectDraft(playerId, listOf("1", "3", "6"))
            as FactoryPlayResult.Accepted

        assertEquals(
            listOf(initial.view.draftSets[0], initial.view.draftSets[2], initial.view.draftSets[5]).map(FactoryRentalSet::setId),
            selected.view.teamSets.map(FactoryRentalSet::setId),
        )
    }

    @Test
    fun `confirmed rentals can be revised from the same draft before the battle starts`() {
        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted
        service.selectDraft(playerId, initial.view.draftSets.take(3).map(FactoryRentalSet::setId))

        val revising = service.reviseSelection(playerId) as FactoryPlayResult.Accepted
        assertEquals(FactoryPlayPhase.INITIAL_DRAFT, revising.view.phase)
        assertEquals(initial.view.draftSets.map(FactoryRentalSet::setId), revising.view.draftSets.map(FactoryRentalSet::setId))

        val replacement = initial.view.draftSets.takeLast(3).map(FactoryRentalSet::setId)
        val ready = service.selectDraft(playerId, replacement) as FactoryPlayResult.Accepted
        assertEquals(replacement, ready.view.teamSets.map(FactoryRentalSet::setId))
    }

    @Test
    fun `rentals cannot be revised after a battle has started`() {
        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        val initial = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted
        service.selectDraft(playerId, initial.view.draftSets.take(3).map(FactoryRentalSet::setId))
        service.beginBattle(playerId)

        val rejected = service.reviseSelection(playerId) as FactoryPlayResult.Rejected
        assertEquals(FactoryPlayError.WRONG_PHASE, rejected.error)
    }

    @Test
    fun `abandoning an unconfirmed draft limits overlap with the next challenge`() {
        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        val first = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted

        service.abandon(playerId)
        val second = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted

        val firstIds = first.view.draftSets.map(FactoryRentalSet::setId).toSet()
        val secondIds = second.view.draftSets.map(FactoryRentalSet::setId).toSet()
        assertTrue(firstIds.intersect(secondIds).size <= 3)
    }

    @Test
    fun `disconnect releases draft history with the in memory session`() {
        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        val first = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted

        service.disconnect(playerId)
        val afterReconnect = service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
            as FactoryPlayResult.Accepted

        assertEquals(
            first.view.draftSets.map(FactoryRentalSet::setId),
            afterReconnect.view.draftSets.map(FactoryRentalSet::setId),
        )
    }

    @Test
    fun `invalid selections and unavailable catalogs fail without creating a run`() {
        val unavailable = playService(null) { FactoryBattleLaunchResult.Started(battleId) }
        assertEquals(
            FactoryPlayError.CATALOG_UNAVAILABLE,
            (unavailable.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.OPEN_LEVEL) as FactoryPlayResult.Rejected).error,
        )

        val service = playService(catalog()) { FactoryBattleLaunchResult.Started(battleId) }
        service.start(playerId, FactoryBattleFormat.SINGLE, FactoryLevelMode.LEVEL_50)
        val rejected = service.selectDraft(playerId, listOf("missing", "also_missing", "still_missing"))
            as FactoryPlayResult.Rejected
        assertEquals(FactoryPlayError.INVALID_SELECTION, rejected.error)
        assertEquals(FactoryPlayPhase.INITIAL_DRAFT, service.status(playerId).phase)

        val outOfRange = service.selectDraft(playerId, listOf("0", "2", "7")) as FactoryPlayResult.Rejected
        assertEquals(FactoryPlayError.INVALID_SELECTION, outOfRange.error)
    }

    private fun playService(
        catalog: FactoryCatalog?,
        catalogRandom: FactoryCatalogRandom = random,
        launcher: FactoryBattleLauncher,
    ): FactoryPlayService {
        lateinit var selector: FactoryDraftSelector
        if (catalog != null) selector = FactoryDraftSelector(catalog, catalogRandom)
        val sessions = FactorySessionService(
            runBattles = FactoryRunBattleService(launcher),
            completions = FactoryBattleCompletionService(
                FactoryBattleRecordService { BattleRecordStats(it.key) },
            ),
            draftProvider = { mode, round, trades -> if (catalog == null) null else selector.select(mode, round, trades) },
        )
        return FactoryPlayService({ catalog }, sessions, catalogRandom)
    }

    private fun catalog(): FactoryCatalog {
        val sets = (1..16).map(::template)
        val members = listOf(
            member("ace", true, setOf(BattleTeamRole.ACE), sets[0]),
            member("enabler", true, setOf(BattleTeamRole.SETUP_ENABLER), sets[1]),
            member("cover", false, setOf(BattleTeamRole.WEAKNESS_COVER), sets[2]),
        )
        val concept = FactoryTrainerConcept(
            conceptId = "ordinary_ace",
            displayNameKey = "factory.concept.ordinary_ace.name",
            descriptionKey = "factory.concept.ordinary_ace.description",
            formats = setOf(FactoryBattleFormat.SINGLE),
            weight = 1,
            aiSkill = 3,
            aiSummary = "Create a safe turn for the ace and cover its main weakness.",
            objectives = setOf(BattleStrategyObjective.SETUP_SWEEP),
            members = members,
        )
        return FactoryCatalog("test", listOf(concept), sets)
    }

    private fun member(
        id: String,
        required: Boolean,
        roles: Set<BattleTeamRole>,
        set: FactoryRentalTemplate,
    ) = FactoryConceptMemberPlan(
        planId = id,
        required = required,
        roles = roles,
        tacticalSummary = "$id supports the team plan",
        preferredMoveIds = setOf(set.moveIds.first()),
        leadPriority = if (BattleTeamRole.SETUP_ENABLER in roles) 100 else 20,
        preservationPriority = if (BattleTeamRole.ACE in roles) 100 else 40,
        setIds = listOf(set.setId),
    )

    private fun template(index: Int) = FactoryRentalTemplate(
        setId = "starter_$index",
        poolGroup = FactoryPoolGroup.STARTER,
        variant = 1,
        speciesId = "cobblemon:species$index",
        moveIds = listOf("cobblemon:move$index"),
        abilityId = "cobblemon:ability$index",
        heldItemId = "cobblemon:item$index",
        natureId = "cobblemon:hardy",
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
