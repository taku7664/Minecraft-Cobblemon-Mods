package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSessionServiceTest {
    private val first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val matchId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")

    @Test
    fun `accepted challenge snapshots both teams launches once and records one paired result`() {
        val snapshots = RecordingSnapshots()
        var launches = 0
        val records = BattleRecordStore()
        val service = service(snapshots, records) {
            launches++
            PvpBattleLaunchResult.Started(battleId)
        }
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))
        service.accept(matchId, second)
        assertEquals(PvpTeamRegistrationMutation.STORED, service.registerTeam(matchId, first, team(first, 1)))
        assertEquals(PvpTeamRegistrationMutation.STORED, service.registerTeam(matchId, second, team(second, 4)))
        val view = requireNotNull(service.viewFor(first))
        assertEquals(second, view.opponentId)
        assertEquals(ids(first, 1), view.ownTeam.members.map(PvpPokemonRegistration::pokemonId))
        assertEquals(listOf("cobblemon:species4", "cobblemon:species5", "cobblemon:species6"), view.opponentPreview.speciesIds)

        assertEquals(PvpSelectionMutation.SELECTION_STORED, service.select(matchId, first, ids(first, 1)))
        assertEquals(PvpSelectionMutation.WAITING_FOR_OPPONENT, service.ready(matchId, first))
        assertEquals(PvpSelectionMutation.SELECTION_STORED, service.select(matchId, second, ids(second, 4)))
        assertEquals(PvpSelectionMutation.BATTLE_STARTED, service.ready(matchId, second))
        assertEquals(1, launches)
        val completion = PvpBattleCompletionSink { winner, loser, format ->
            PvpBattleRecordService(records::recordCompletedBattles).recordResult(winner, loser, format)
        }
        assertTrue(service.completeBattle(matchId, battleId, first, second, completion))
        assertFalse(service.completeBattle(matchId, battleId, first, second, completion))

        assertEquals(setOf(first, second), snapshots.discarded)
        assertEquals(1, records.get(recordKey(first)).totalWins)
        assertEquals(1, records.get(recordKey(second)).totalLosses)
    }

    @Test
    fun `failed launch remains ready and can be retried without resnapshotting`() {
        val snapshots = RecordingSnapshots()
        var available = false
        val service = service(snapshots, BattleRecordStore()) {
            if (available) PvpBattleLaunchResult.Started(battleId) else PvpBattleLaunchResult.Unavailable
        }
        ready(service)

        assertEquals(PvpSelectionMutation.SELECTION_STORED, service.select(matchId, second, ids(second, 4)))
        assertEquals(PvpSelectionMutation.BATTLE_UNAVAILABLE, service.ready(matchId, second))
        available = true
        assertEquals(PvpSelectionMutation.BATTLE_STARTED, service.launchReady(matchId))
        assertEquals(2, snapshots.captured.size)
    }

    @Test
    fun `registration failure and cancellation fail closed and discard captured snapshots`() {
        val snapshots = RecordingSnapshots(rejectedPlayer = second)
        val service = service(snapshots, BattleRecordStore()) { PvpBattleLaunchResult.Unavailable }
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))
        service.accept(matchId, second)

        assertEquals(PvpTeamRegistrationMutation.STORED, service.registerTeam(matchId, first, team(first, 1)))
        assertEquals(PvpTeamRegistrationMutation.SNAPSHOT_REJECTED, service.registerTeam(matchId, second, team(second, 4)))
        assertTrue(service.cancel(matchId, first) is PvpChallengeMutationResult.Applied)
        assertEquals(setOf(first, second), snapshots.discarded)
    }

    @Test
    fun `target can reject a pending challenge and both players become available`() {
        val service = service(RecordingSnapshots(), BattleRecordStore()) { PvpBattleLaunchResult.Unavailable }
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))

        assertTrue(service.reject(matchId, second) is PvpChallengeMutationResult.Applied)
        assertTrue(
            service.invite(PvpChallengeRequest(UUID.randomUUID(), first, second, PvpBattleFormat.DOUBLE)) is
                PvpChallengeMutationResult.Applied,
        )
    }

    @Test
    fun `battle cancellation releases both players without recording a result`() {
        val snapshots = RecordingSnapshots()
        val records = BattleRecordStore()
        val service = service(snapshots, records) { PvpBattleLaunchResult.Started(battleId) }
        ready(service)
        assertEquals(PvpSelectionMutation.SELECTION_STORED, service.select(matchId, second, ids(second, 4)))
        assertEquals(PvpSelectionMutation.BATTLE_STARTED, service.ready(matchId, second))

        assertTrue(service.cancelBattle(matchId, battleId))
        assertFalse(service.cancelBattle(matchId, battleId))
        assertTrue(records.all().isEmpty())
        assertEquals(setOf(first, second), snapshots.discarded)
        assertTrue(
            service.invite(PvpChallengeRequest(UUID.randomUUID(), first, second, PvpBattleFormat.SINGLE)) is
                PvpChallengeMutationResult.Applied,
        )
    }

    @Test
    fun `entry deadline auto selects registered order and starts when both players time out`() {
        var now = 0L
        val snapshots = RecordingSnapshots()
        var prepared: PvpPreparedBattle<String>? = null
        val service = PvpSessionService(
            snapshots = snapshots,
            launcher = PvpBattleLauncher(snapshots, PvpBattleRuntime { battle ->
                prepared = battle
                PvpBattleLaunchResult.Started(battleId)
            }),
            currentTimeMillis = { now },
        )
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))
        service.accept(matchId, second)
        service.registerTeam(matchId, first, team(first, 1))
        service.registerTeam(matchId, second, team(second, 4))

        now = 90_000L
        val resolution = service.expireEntrySelections().single()

        assertEquals(setOf(first, second), resolution.autoSelectedPlayerIds)
        assertEquals(PvpSelectionMutation.BATTLE_STARTED, resolution.launchResult)
        assertEquals(ids(first, 1), prepared?.request?.firstSelection?.members?.map(PvpPokemonRegistration::pokemonId))
        assertEquals(ids(second, 4), prepared?.request?.secondSelection?.members?.map(PvpPokemonRegistration::pokemonId))
        assertTrue(service.expireEntrySelections().isEmpty())
    }

    @Test
    fun `a ready player can unready and edit until both players are ready`() {
        val service = service(RecordingSnapshots(), BattleRecordStore()) { PvpBattleLaunchResult.Started(battleId) }
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))
        service.accept(matchId, second)
        service.registerTeam(matchId, first, team(first, 1))
        service.registerTeam(matchId, second, team(second, 4))
        service.select(matchId, first, ids(first, 1))

        assertEquals(PvpSelectionMutation.WAITING_FOR_OPPONENT, service.ready(matchId, first))
        assertTrue(requireNotNull(service.viewFor(first)).ready)
        assertTrue(service.unready(matchId, first))
        assertFalse(requireNotNull(service.viewFor(first)).ready)
        assertEquals(PvpSelectionMutation.SELECTION_STORED, service.select(matchId, first, ids(first, 1).reversed()))
    }

    @Test
    fun `a completed match frees its id so the same room can host a rematch`() {
        val service = service(RecordingSnapshots(), BattleRecordStore()) { PvpBattleLaunchResult.Started(battleId) }
        ready(service)
        service.select(matchId, second, ids(second, 4))
        assertEquals(PvpSelectionMutation.BATTLE_STARTED, service.ready(matchId, second))
        assertTrue(
            service.completeBattle(matchId, battleId, first, second, PvpBattleCompletionSink { _, _, _ -> }),
        )

        assertNull(service.challenge(matchId))
        assertTrue(
            service.invite(PvpChallengeRequest(matchId, second, first, PvpBattleFormat.SINGLE)) is
                PvpChallengeMutationResult.Applied,
        )
        assertTrue(service.accept(matchId, first) is PvpChallengeMutationResult.Applied)
    }

    @Test
    fun `a cancelled match frees its id so the same room can start again`() {
        val service = service(RecordingSnapshots(), BattleRecordStore()) { PvpBattleLaunchResult.Started(battleId) }
        ready(service)
        service.select(matchId, second, ids(second, 4))
        service.ready(matchId, second)
        assertTrue(service.cancelBattle(matchId, battleId))

        assertNull(service.challenge(matchId))
        assertTrue(
            service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.DOUBLE)) is
                PvpChallengeMutationResult.Applied,
        )
    }

    private fun ready(service: PvpSessionService<String>) {
        service.invite(PvpChallengeRequest(matchId, first, second, PvpBattleFormat.SINGLE))
        service.accept(matchId, second)
        service.registerTeam(matchId, first, team(first, 1))
        service.registerTeam(matchId, second, team(second, 4))
        service.select(matchId, first, ids(first, 1))
        service.ready(matchId, first)
    }

    private fun service(
        snapshots: RecordingSnapshots,
        records: BattleRecordStore,
        runtime: (PvpPreparedBattle<String>) -> PvpBattleLaunchResult,
    ) = PvpSessionService(
        snapshots = snapshots,
        launcher = PvpBattleLauncher(snapshots, PvpBattleRuntime(runtime)),
    )

    private fun team(playerId: UUID, start: Int): PvpRegisteredTeam =
        (PvpTeamRules.register(
            (start until start + 3).map { index -> pokemon(playerId, index) },
            PvpBattleFormat.SINGLE,
        ) as PvpTeamRegistrationResult.Accepted).team

    private fun ids(playerId: UUID, start: Int): List<UUID> =
        (start until start + 3).map { index -> pokemonId(playerId, index) }

    private fun pokemon(playerId: UUID, index: Int) = PvpPokemonRegistration(
        pokemonId(playerId, index),
        "cobblemon:species$index",
        "cobblemon:item$index",
        50,
    )

    private fun pokemonId(playerId: UUID, index: Int) = UUID(playerId.mostSignificantBits, index.toLong())

    private fun recordKey(playerId: UUID) = jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey(
        playerId,
        jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory("pvp", "single"),
    )

    private class RecordingSnapshots(
        private val rejectedPlayer: UUID? = null,
    ) : PvpSessionSnapshots<String>, PvpBattleTeamMaterializer<String> {
        val captured = LinkedHashSet<UUID>()
        val discarded = LinkedHashSet<UUID>()

        override fun snapshot(playerId: UUID, team: PvpRegisteredTeam): PvpRegisteredTeamSnapshotResult =
            if (playerId == rejectedPlayer) {
                PvpRegisteredTeamSnapshotResult.Rejected(team.members.first().pokemonId)
            } else {
                captured += playerId
                PvpRegisteredTeamSnapshotResult.Stored
            }

        override fun materialize(
            playerId: UUID,
            selection: PvpSelectedTeam,
        ): PvpRegisteredBattleTeamResult<String> = PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId"))

        override fun discard(playerId: UUID) {
            discarded += playerId
        }
    }
}
