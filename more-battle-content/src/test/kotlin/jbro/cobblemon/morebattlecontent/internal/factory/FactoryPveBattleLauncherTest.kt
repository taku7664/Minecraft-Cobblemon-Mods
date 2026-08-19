package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryPveBattleLauncherTest {
    private val playerId = UUID.randomUUID()
    private val battleId = UUID.randomUUID()

    @Test
    fun `materializes complete rental teams before starting runtime`() {
        val starts = ArrayList<FactoryPreparedPveBattle<String>>()
        val launcher = FactoryPveBattleLauncher(
            playerMemberFactory = { set, mode -> "player:${set.setId}:${mode.battleLevel}" },
            opponentMemberFactory = { set, mode -> "opponent:${set.setId}:${mode.battleLevel}" },
            runtime = FactoryPveBattleRuntime { prepared ->
                starts += prepared
                FactoryBattleLaunchResult.Started(battleId)
            },
        )
        val request = request()

        assertEquals(FactoryBattleLaunchResult.Started(battleId), launcher.launch(request))
        assertEquals(listOf("player:set1:100", "player:set2:100", "player:set3:100"), starts.single().playerTeam)
        assertEquals(
            listOf("opponent:set7:100", "opponent:set8:100", "opponent:set9:100"),
            starts.single().opponentTeam.values.toList(),
        )
        assertEquals(request.opponentTeam.keys, starts.single().opponentTeam.keys)
        assertEquals(1, starts.single().battleNumber)
        assertEquals(BattleTrainerTier.ADVANCED, starts.single().trainerProfile.difficulty.tier)
        assertEquals(BattleBrainContentIds.BATTLE_FACTORY, starts.single().brainSelectionContext.contentId)
        assertEquals(BattleEncounterRole.REGULAR, starts.single().brainSelectionContext.encounterRole)
    }

    @Test
    fun `factory heads always use boss difficulty`() {
        val starts = ArrayList<FactoryPreparedPveBattle<String>>()
        val launcher = FactoryPveBattleLauncher(
            playerMemberFactory = { set, _ -> set.setId },
            opponentMemberFactory = { set, _ -> set.setId },
            runtime = FactoryPveBattleRuntime { prepared ->
                starts += prepared
                FactoryBattleLaunchResult.Started(battleId)
            },
        )

        launcher.launch(request(battleNumber = 21, aiSkill = 3))

        assertEquals(BattleTrainerTier.BOSS, starts.single().trainerProfile.difficulty.tier)
        assertEquals(BattleEncounterRole.BOSS, starts.single().brainSelectionContext.encounterRole)
    }

    @Test
    fun `ordinary ai skill five opponent is boss difficulty but remains a regular encounter`() {
        val starts = ArrayList<FactoryPreparedPveBattle<String>>()
        val launcher = FactoryPveBattleLauncher(
            playerMemberFactory = { set, _ -> set.setId },
            opponentMemberFactory = { set, _ -> set.setId },
            runtime = FactoryPveBattleRuntime { prepared ->
                starts += prepared
                FactoryBattleLaunchResult.Started(battleId)
            },
        )

        launcher.launch(request(battleNumber = 20, aiSkill = 5))

        assertEquals(BattleTrainerTier.BOSS, starts.single().trainerProfile.difficulty.tier)
        assertEquals(BattleEncounterRole.REGULAR, starts.single().brainSelectionContext.encounterRole)
    }

    @Test
    fun `materialization failure prevents partial battle start`() {
        var starts = 0
        val launcher = FactoryPveBattleLauncher(
            playerMemberFactory = { set, _ -> set.setId },
            opponentMemberFactory = { set, _ ->
                if (set.setId == "set8") error("invalid rental")
                set.setId
            },
            runtime = FactoryPveBattleRuntime {
                starts++
                FactoryBattleLaunchResult.Started(battleId)
            },
        )

        assertEquals(FactoryBattleLaunchResult.Unavailable, launcher.launch(request()))
        assertEquals(0, starts)
    }

    @Test
    fun `run service marks active only after a successful launch and preserves ready state on failure`() {
        val session = session()
        val successful = FactoryRunBattleService(
            FactoryBattleLauncher { FactoryBattleLaunchResult.Started(battleId) },
        )

        assertEquals(
            FactoryBattleLaunchResult.Started(battleId),
            successful.begin(playerId, session, opponent(), "trainer.factory.regular", 3, strategyBrief()),
        )
        assertEquals(battleId, session.activeBattleId)
        assertEquals(FactoryRunPhase.IN_BATTLE, session.phase)

        val ready = session()
        val unavailable = FactoryRunBattleService(
            FactoryBattleLauncher { FactoryBattleLaunchResult.Unavailable },
        )
        assertEquals(
            FactoryBattleLaunchResult.Unavailable,
            unavailable.begin(playerId, ready, opponent(), "trainer.factory.regular", 3, strategyBrief()),
        )
        assertTrue(ready.activeBattleId == null)
        assertEquals(FactoryRunPhase.READY, ready.phase)
    }

    private fun request(battleNumber: Int = 1, aiSkill: Int = 3) = FactoryBattleLaunchRequest(
        playerId = playerId,
        runId = UUID.randomUUID(),
        battleNumber = battleNumber,
        playerTeam = team(1),
        levelMode = FactoryLevelMode.OPEN_LEVEL,
        opponentTeam = opponent(),
        trainerNameKey = "trainer.factory.regular",
        aiSkill = aiSkill,
        strategyBrief = strategyBrief(),
    )

    private fun session() = FactoryRunSession(
        UUID.randomUUID(),
        team(1),
        FactoryLevelMode.LEVEL_50,
        healRentals = {},
    )

    private fun team(start: Int) = FactoryRentalDraft((start until start + 6).map(::rental))
        .select((start until start + 3).map { "set$it" }, FactoryBattleFormat.SINGLE)

    private fun opponent() = (7..9).associate { index -> UUID(1, index.toLong()) to rental(index) }

    private fun strategyBrief() = jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief(
        strategyId = "mbc:test_factory",
        displayNameKey = "strategy.mbc.test_factory.name",
        descriptionKey = "strategy.mbc.test_factory.description",
        aiSummary = "Apply balanced pressure.",
        objectives = setOf(jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective.BALANCED_PRESSURE),
    )

    private fun rental(index: Int) = FactoryRentalSet(
        setId = "set$index",
        speciesId = "cobblemon:species$index",
        moveIds = listOf("cobblemon:move$index"),
        abilityId = "cobblemon:ability$index",
        heldItemId = "cobblemon:item$index",
        natureId = "cobblemon:nature$index",
        ivs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
