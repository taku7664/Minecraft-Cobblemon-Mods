package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `diagnostics failure cannot replace an unavailable player team`() {
        val launcher = PvpBattleLauncher<String>(
            materialize = { _, _ -> PvpRegisteredBattleTeamResult.NoSnapshot },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Unavailable },
            diagnostics = { throw NoSuchMethodError("diagnostics API drift") },
        )

        assertEquals(PvpBattleLaunchResult.Unavailable, launcher.launch(request()))
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
    fun `placement activation linkage failure cannot leave a started battle alive`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean {
                        events += "activate"
                        throw NoSuchMethodError("placement API drift")
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
    fun `fatal placement activation failure still ends the battle and rolls placement back`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val failure = AssertionError("fatal activation")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = throw failure

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
            abortBattle = { startedBattleId -> events += "abort-$startedBattleId" },
        )

        assertEquals(failure, assertThrows(AssertionError::class.java) { launcher.launch(request()) })
        assertEquals(listOf("abort-$battleId", "rollback"), events)
    }

    @Test
    fun `fatal abort failure cannot skip placement rollback`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val failure = AssertionError("fatal abort")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = false

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
            abortBattle = { throw failure },
        )

        assertEquals(failure, assertThrows(AssertionError::class.java) { launcher.launch(request()) })
        assertEquals(listOf("rollback"), events)
    }

    @Test
    fun `activation cleanup failures preserve the activation failure`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val activationFailure = IllegalStateException("activate")
        val abortFailure = IllegalArgumentException("abort")
        val rollbackFailure = UnsupportedOperationException("rollback")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = throw activationFailure

                    override fun rollback() {
                        throw rollbackFailure
                    }
                }
            },
            abortBattle = { throw abortFailure },
        )

        val thrown = assertThrows(IllegalStateException::class.java) { launcher.launch(request()) }

        assertEquals(activationFailure, thrown)
        assertEquals(listOf(abortFailure, rollbackFailure), thrown.suppressed.toList())
    }

    @Test
    fun `diagnostics failure cannot skip activation cleanup`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val events = ArrayList<String>()
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = false

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
            abortBattle = { startedBattleId -> events += "abort-$startedBattleId" },
            diagnostics = { throw IllegalStateException("diagnostics") },
        )

        assertEquals(PvpBattleLaunchResult.Unavailable, launcher.launch(request()))
        assertEquals(listOf("abort-$battleId", "rollback"), events)
    }

    @Test
    fun `fatal diagnostics failure cannot replace activation and cleanup failures`() {
        val battleId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val activationFailure = IllegalStateException("activate")
        val abortFailure = IllegalArgumentException("abort")
        val diagnosticsFailure = AssertionError("diagnostics")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { PvpBattleLaunchResult.Started(battleId) },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = throw activationFailure
                    override fun rollback() = Unit
                }
            },
            abortBattle = { throw abortFailure },
            diagnostics = { throw diagnosticsFailure },
        )

        val thrown = assertThrows(IllegalStateException::class.java) { launcher.launch(request()) }

        assertEquals(activationFailure, thrown)
        assertEquals(listOf(abortFailure, diagnosticsFailure), thrown.suppressed.toList())
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

    @Test
    fun `runtime linkage failure rolls prepared placement back`() {
        val events = ArrayList<String>()
        val failure = NoSuchMethodError("Cobblemon API drift")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { throw failure },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = true

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
        )

        assertEquals(failure, assertThrows(NoSuchMethodError::class.java) { launcher.launch(request()) })
        assertEquals(listOf("rollback"), events)
    }

    @Test
    fun `fatal runtime failure rolls prepared placement back before propagating`() {
        val events = ArrayList<String>()
        val failure = AssertionError("fatal runtime")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { throw failure },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = true

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            },
        )

        assertEquals(failure, assertThrows(AssertionError::class.java) { launcher.launch(request()) })
        assertEquals(listOf("rollback"), events)
    }

    @Test
    fun `placement rollback failure cannot hide the runtime failure`() {
        val runtimeFailure = IllegalStateException("runtime")
        val rollbackFailure = IllegalArgumentException("rollback")
        val launcher = PvpBattleLauncher(
            materialize = { playerId, _ -> PvpRegisteredBattleTeamResult.Created(listOf("copy-$playerId")) },
            runtime = PvpBattleRuntime { throw runtimeFailure },
            placement = PvpBattlePlacement {
                object : PvpPreparedBattlePlacement {
                    override fun activate(startedBattleId: UUID): Boolean = true

                    override fun rollback() {
                        throw rollbackFailure
                    }
                }
            },
        )

        val thrown = assertThrows(IllegalStateException::class.java) { launcher.launch(request()) }

        assertEquals(runtimeFailure, thrown)
        assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
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
