package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalog
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentProfile
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentRandom
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerStatSpread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TowerPveBattleLauncherTest {
    private val playerId = UUID.randomUUID()
    private val battleId = UUID.randomUUID()
    private val selection = TowerSelectedTeam(
        TowerBattleFormat.SINGLE,
        (1..3).map(::registration),
    )

    @Test
    fun `prepares exact mechanic opponent and passes complete teams to runtime`() {
        val starts = ArrayList<TowerPreparedPveBattle<String, String>>()
        val launcher = TowerPveBattleLauncher(
            registeredTeamMaterializer = { _, _ ->
                TowerRegisteredBattleTeamResult.Created(listOf("player_1", "player_2", "player_3"))
            },
            catalogSource = { catalog(MajorBattleMechanic.MEGA) },
            opponentMemberFactory = { set -> "opponent:${set.setId}" },
            runtime = TowerPveBattleRuntime { prepared ->
                starts += prepared
                TowerBattleLaunchResult.Started(battleId)
            },
            random = FixedRandom,
        )

        val result = launcher.launch(request(MajorBattleMechanic.MEGA))

        assertEquals(TowerBattleLaunchResult.Started(battleId), result)
        assertEquals(1, starts.size)
        assertEquals(listOf("player_1", "player_2", "player_3"), starts.single().playerTeam)
        assertEquals(3, starts.single().opponentTeam.size)
        assertEquals("mega_profile", starts.single().profile.profileId)
        assertEquals(BattleTrainerTier.INTRODUCTORY, starts.single().trainerProfile.difficulty.tier)
        assertEquals(BattleBrainContentIds.BATTLE_TOWER, starts.single().brainSelectionContext.contentId)
        assertEquals(BattleEncounterRole.REGULAR, starts.single().brainSelectionContext.encounterRole)
        assertEquals(MajorBattleMechanic.MEGA, starts.single().mechanic)
    }

    @Test
    fun `successful launches avoid recent trainers and opponent species`() {
        val starts = ArrayList<TowerPreparedPveBattle<String, String>>()
        val sets = (1..6).map(::set)
        val profiles = listOf("first", "second").map { id ->
            TowerOpponentProfile(
                profileId = id,
                displayNameKey = "trainer.test.$id",
                rankIds = listOf(TowerRank.RANK_1),
                format = TowerBattleFormat.SINGLE,
                opponentKind = TowerOpponentKind.REGULAR,
                mechanic = MajorBattleMechanic.MEGA,
                weight = 1,
                aiSkill = 1,
                theme = "balanced",
                setIds = sets.map(TowerPokemonSet::setId),
            )
        }
        val launcher = TowerPveBattleLauncher(
            registeredTeamMaterializer = { _, _ ->
                TowerRegisteredBattleTeamResult.Created(listOf("player_1", "player_2", "player_3"))
            },
            catalogSource = { TowerOpponentCatalog("test", profiles, sets) },
            opponentMemberFactory = { it.speciesId },
            runtime = TowerPveBattleRuntime { prepared ->
                starts += prepared
                TowerBattleLaunchResult.Started(UUID.randomUUID())
            },
            random = FixedRandom,
        )

        launcher.launch(request(MajorBattleMechanic.MEGA))
        launcher.launch(request(MajorBattleMechanic.MEGA))

        assertEquals(listOf("first", "second"), starts.map { it.profile.profileId })
        assertTrue(
            starts[0].opponentTeam.toSet().intersect(starts[1].opponentTeam.toSet()).isEmpty(),
            "Consecutive Tower teams must not repeat a species while a fresh legal team exists",
        )
    }

    @Test
    fun `promotion battle is exposed to brain selection as an actual boss encounter`() {
        val starts = ArrayList<TowerPreparedPveBattle<String, String>>()
        val progress = TowerProgress(TowerBattleFormat.SINGLE, TowerRank.RANK_3, rankPoints = 1)
        val launcher = TowerPveBattleLauncher(
            registeredTeamMaterializer = { _, _ ->
                TowerRegisteredBattleTeamResult.Created(listOf("player_1", "player_2", "player_3"))
            },
            catalogSource = { catalog(MajorBattleMechanic.MEGA, TowerRank.RANK_3, TowerOpponentKind.TIER_BOSS) },
            opponentMemberFactory = { set -> "opponent:${set.setId}" },
            runtime = TowerPveBattleRuntime { prepared ->
                starts += prepared
                TowerBattleLaunchResult.Started(battleId)
            },
            random = FixedRandom,
        )

        launcher.launch(request(MajorBattleMechanic.MEGA, progress))

        assertEquals(BattleTrainerTier.BOSS, starts.single().trainerProfile.difficulty.tier)
        assertEquals(BattleEncounterRole.BOSS, starts.single().brainSelectionContext.encounterRole)
    }

    @Test
    fun `fails closed before runtime when snapshot or exact mechanic catalog is unavailable`() {
        var starts = 0
        val runtime = TowerPveBattleRuntime<String, String> {
            starts++
            TowerBattleLaunchResult.Started(battleId)
        }
        val noSnapshot = TowerPveBattleLauncher(
            registeredTeamMaterializer = { _, _ -> TowerRegisteredBattleTeamResult.NoSnapshot },
            catalogSource = { catalog(MajorBattleMechanic.MEGA) },
            opponentMemberFactory = { it.setId },
            runtime = runtime,
            random = FixedRandom,
        )
        val wrongMechanic = TowerPveBattleLauncher(
            registeredTeamMaterializer = { _, _ ->
                TowerRegisteredBattleTeamResult.Created(listOf("a", "b", "c"))
            },
            catalogSource = { catalog(MajorBattleMechanic.DYNAMAX) },
            opponentMemberFactory = { it.setId },
            runtime = runtime,
            random = FixedRandom,
        )

        assertEquals(TowerBattleLaunchResult.Unavailable, noSnapshot.launch(request(MajorBattleMechanic.MEGA)))
        assertEquals(TowerBattleLaunchResult.Unavailable, wrongMechanic.launch(request(MajorBattleMechanic.MEGA)))
        assertEquals(0, starts)
    }

    private fun request(
        mechanic: MajorBattleMechanic,
        progress: TowerProgress = TowerProgress.initial(TowerBattleFormat.SINGLE),
    ) = TowerBattleLaunchRequest(
        playerId,
        progress,
        selection,
        mechanic,
    )

    private fun catalog(
        mechanic: MajorBattleMechanic,
        rank: TowerRank = TowerRank.RANK_1,
        opponentKind: TowerOpponentKind = TowerOpponentKind.REGULAR,
    ): TowerOpponentCatalog {
        val sets = (1..6).map(::set)
        val profile = TowerOpponentProfile(
            profileId = "${mechanic.id}_profile",
            displayNameKey = "trainer.test.${mechanic.id}",
            rankIds = listOf(rank),
            format = TowerBattleFormat.SINGLE,
            opponentKind = opponentKind,
            mechanic = mechanic,
            weight = 1,
            aiSkill = 1,
            theme = "balanced",
            setIds = sets.map(TowerPokemonSet::setId),
        )
        return TowerOpponentCatalog("test", listOf(profile), sets)
    }

    private fun registration(index: Int) = TowerPokemonRegistration(
        UUID(0, index.toLong()),
        "cobblemon:species_$index",
        "minecraft:item_$index",
        50,
    )

    private fun set(index: Int) = TowerPokemonSet(
        setId = "set_$index",
        setTier = 1,
        speciesId = "cobblemon:opponent_$index",
        formId = null,
        abilityId = null,
        natureId = "cobblemon:hardy",
        heldItemId = "minecraft:opponent_item_$index",
        moves = listOf("cobblemon:tackle"),
        ivs = TowerStatSpread(15, 15, 15, 15, 15, 15),
        evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
    )

    private object FixedRandom : TowerOpponentRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int {
            assertTrue(bound > 0)
            return 0
        }
    }
}
