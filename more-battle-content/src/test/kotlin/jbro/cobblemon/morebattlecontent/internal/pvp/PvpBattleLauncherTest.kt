package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpBattleLauncherTest {
    private val first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @Test
    fun `starts only after both registered selections materialize`() {
        val request = request()
        var prepared: PvpPreparedBattle<String>? = null
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { battle ->
                prepared = battle
                PvpBattleLaunchResult.Started(UUID.fromString("99999999-9999-9999-9999-999999999999"))
            },
        )

        val result = launcher.launch(request)

        assertTrue(result is PvpBattleLaunchResult.Started)
        assertEquals(listOf("copy-$first"), prepared?.firstTeam)
        assertEquals(listOf("copy-$second"), prepared?.secondTeam)
        assertEquals(PvpBattleFormat.SINGLE, prepared?.request?.format)
        assertEquals(PvpRoomDefaults.ENABLED_MECHANICS, prepared?.request?.enabledMechanics)
    }

    @Test
    fun `a missing snapshot on either side prevents battle creation`() {
        val request = request()
        var runtimeCalled = false
        val launcher = PvpBattleLauncher<String>(
            materialize = { playerId, _ ->
                if (playerId == first) PvpRegisteredBattleTeamResult.Created(listOf("first"))
                else PvpRegisteredBattleTeamResult.NoSnapshot
            },
            runtime = PvpBattleRuntime {
                runtimeCalled = true
                PvpBattleLaunchResult.Unavailable
            },
        )

        val result = launcher.launch(request)

        assertEquals(PvpBattleLaunchResult.Unavailable, result)
        assertFalse(runtimeCalled)
    }

    @Test
    fun `prepared teams detach mutable materializer collections`() {
        val request = request()
        val firstCopies = mutableListOf("first")
        var prepared: PvpPreparedBattle<String>? = null
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ ->
                PvpRegisteredBattleTeamResult.Created(if (playerId == first) firstCopies else listOf("second"))
            },
            runtime = PvpBattleRuntime { battle ->
                prepared = battle
                PvpBattleLaunchResult.Started(UUID.randomUUID())
            },
        )

        launcher.launch(request)
        firstCopies += "mutated"

        assertEquals(listOf("first"), prepared?.firstTeam)
    }

    @Test
    fun `room placement completes before the battle runtime creates actors`() {
        val events = ArrayList<String>()
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ ->
                events += "materialize-$playerId"
                PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId"))
            },
            runtime = PvpBattleRuntime {
                events += "runtime"
                PvpBattleLaunchResult.Started(battleId)
            },
            placement = PvpBattlePlacement {
                events += "prepare-placement"
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean {
                        events += "activate-$startedBattleId"
                        return true
                    }

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
        )

        assertEquals(PvpBattleLaunchResult.Started(battleId), launcher.launch(request()))
        assertEquals(
            listOf(
                "materialize-$first",
                "materialize-$second",
                "prepare-placement",
                "runtime",
                "activate-$battleId",
            ),
            events,
        )
    }

    @Test
    fun `failed placement activation ends the started battle and rolls placement back`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = false.also { events += "activate" }

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
            abortBattle = { startedBattleId -> events += "abort-$startedBattleId" },
        )

        assertEquals(PvpBattleLaunchResult.Unavailable, launcher.launch(request()))
        assertEquals(listOf("activate", "abort-$battleId", "rollback"), events)
    }

    @Test
    fun `placement activation exception cannot leave a started battle alive`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean {
                        events += "activate"
                        error("placement failed")
                    }

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
            abortBattle = { startedBattleId -> events += "abort-$startedBattleId" },
        )

        assertEquals(PvpBattleLaunchResult.Unavailable, launcher.launch(request()))
        assertEquals(listOf("activate", "abort-$battleId", "rollback"), events)
    }

    @Test
    fun `failed placement preparation prevents battle creation`() {
        var runtimeCalled = false
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime {
                runtimeCalled = true
                PvpBattleLaunchResult.Started(UUID.randomUUID())
            },
            placement = PvpBattlePlacement { null },
        )

        assertEquals(PvpBattleLaunchResult.Unavailable, launcher.launch(request()))
        assertFalse(runtimeCalled)
    }

    private fun request(): PvpBattleLaunchRequest = PvpBattleLaunchRequest(
        matchId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        firstPlayerId = first,
        secondPlayerId = second,
        format = PvpBattleFormat.SINGLE,
        firstSelection = selection(first, 1),
        secondSelection = selection(second, 4),
    )

    private fun selection(playerId: UUID, firstIndex: Int): PvpSelectedTeam = PvpSelectedTeam(
        PvpBattleFormat.SINGLE,
        (firstIndex until firstIndex + 3).map { index ->
            PvpPokemonRegistration(UUID(playerId.mostSignificantBits, index.toLong()), "cobblemon:species$index", null, 50)
        },
    )
}
