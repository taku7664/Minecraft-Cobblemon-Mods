package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpLoungeCoordinatorTest {
    private val roomId = UUID(0, 100)
    private val left = UUID(0, 1)
    private val right = UUID(0, 2)
    private val viewer = UUID(0, 3)

    @Test
    fun `start allocates one isolated arena and moves players and bodyless spectators`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)

        assertTrue(coordinator.start(room(), UUID(0, 900)))

        val lease = requireNotNull(coordinator.leaseFor(roomId))
        assertEquals(listOf(lease), gateway.ensured)
        assertEquals(listOf(left to PvpRoomSide.LEFT, right to PvpRoomSide.RIGHT), gateway.competitors)
        assertEquals(listOf(viewer), gateway.spectators)
        assertEquals(listOf(viewer to left), gateway.spectating)
        assertEquals(
            listOf(
                ArenaHologramEvent(left, UUID(0, 900), PvpRoomSide.LEFT),
                ArenaHologramEvent(right, UUID(0, 900), PvpRoomSide.RIGHT),
                ArenaHologramEvent(viewer, UUID(0, 900), PvpRoomSide.LEFT),
            ),
            gateway.shownArenaHolograms,
        )
        assertEquals(setOf(left, right, viewer), coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `active public room can add a spectator and finish restores everyone and releases the arena`() {
        val gateway = RecordingGateway()
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)
        coordinator.start(room(spectators = emptyList()), UUID(0, 900))

        assertTrue(coordinator.addSpectator(roomId, viewer, left))
        assertEquals(ArenaHologramEvent(viewer, UUID(0, 900), PvpRoomSide.LEFT), gateway.shownArenaHolograms.last())
        assertTrue(coordinator.finish(roomId))

        assertEquals(setOf(left, right, viewer), gateway.restored.toSet())
        assertEquals(setOf(left, right, viewer), gateway.hiddenArenaHolograms.map { it.first }.toSet())
        assertTrue(gateway.hiddenArenaHolograms.all { it.second == UUID(0, 900) })
        assertNull(pool.leaseFor(roomId))
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
        assertFalse(coordinator.finish(roomId))
    }

    @Test
    fun `offline restore remains pending until that player is available`() {
        val gateway = RecordingGateway().apply { unavailableForRestore += viewer }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(), UUID(0, 900))

        coordinator.finish(roomId)

        assertEquals(setOf(viewer), coordinator.pendingReturnPlayerIds())
        gateway.unavailableForRestore.clear()
        assertTrue(coordinator.restorePending(viewer))
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
    }

    @Test
    fun `spectator exit succeeds while a failed return remains queued for retry`() {
        val gateway = RecordingGateway().apply { unavailableForRestore += viewer }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(), UUID(0, 900))

        assertTrue(coordinator.removeSpectator(roomId, viewer))
        assertEquals(setOf(viewer), coordinator.pendingReturnPlayerIds().intersect(setOf(viewer)))

        gateway.unavailableForRestore.clear()
        assertTrue(coordinator.restorePending(viewer))
        assertFalse(viewer in coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `disconnecting spectator performs packet free cleanup and keeps return pending`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        val battleId = UUID(0, 900)
        coordinator.start(room(), battleId)
        gateway.hiddenArenaHolograms.clear()

        assertTrue(coordinator.disconnectSpectator(roomId, viewer))

        assertEquals(listOf(viewer to battleId), gateway.disconnectedSpectators)
        assertTrue(gateway.hiddenArenaHolograms.isEmpty())
        assertTrue(gateway.stoppedSpectating.isEmpty())
        assertEquals(setOf(viewer), coordinator.pendingReturnPlayerIds().intersect(setOf(viewer)))
    }

    @Test
    fun `disconnect cleanup failure cannot keep the spectator registered`() {
        val gateway = RecordingGateway().apply { failDisconnect = true }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(), UUID(0, 900))

        assertTrue(coordinator.disconnectSpectator(roomId, viewer))
        assertFalse(coordinator.disconnectSpectator(roomId, viewer))
        assertTrue(viewer in coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `prepare moves competitors before battle start and activate moves spectators afterward`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        val battleId = UUID(0, 900)

        assertTrue(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))
        assertEquals(listOf(left to PvpRoomSide.LEFT, right to PvpRoomSide.RIGHT), gateway.competitors)
        assertTrue(gateway.spectators.isEmpty())
        assertTrue(gateway.spectating.isEmpty())

        assertTrue(coordinator.activate(roomId, battleId))
        assertEquals(listOf(viewer), gateway.spectators)
        assertEquals(listOf(viewer to left), gateway.spectating)
        assertEquals(setOf(roomId), coordinator.activeRoomIds())
    }

    @Test
    fun `rollback preparation restores captured players and releases the arena`() {
        val gateway = RecordingGateway()
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)

        assertTrue(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))
        assertTrue(coordinator.rollbackPreparation(roomId))

        assertEquals(setOf(left, right, viewer), gateway.restored.toSet())
        assertNull(pool.leaseFor(roomId))
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
    }

    @Test
    fun `available pending returns are restored only when the server tick sees the player online`() {
        val gateway = RecordingGateway().apply { unavailableForRestore += setOf(left, right, viewer) }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(), UUID(0, 900))
        coordinator.finish(roomId)
        gateway.restored.clear()
        gateway.unavailableForRestore.clear()

        assertEquals(emptySet<UUID>(), coordinator.restoreAvailable { false })
        assertEquals(setOf(left, right, viewer), coordinator.pendingReturnPlayerIds())

        assertEquals(setOf(left), coordinator.restoreAvailable { it == left })
        assertEquals(listOf(left), gateway.restored)
        assertEquals(setOf(right, viewer), coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `server tick restore never removes players from an active or prepared lounge`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)

        assertTrue(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))
        assertEquals(emptySet<UUID>(), coordinator.restoreAvailable { true })
        assertTrue(gateway.restored.isEmpty())

        assertTrue(coordinator.activate(roomId, UUID(0, 900)))
        assertEquals(emptySet<UUID>(), coordinator.restoreAvailable { true })
        assertTrue(gateway.restored.isEmpty())
        assertEquals(setOf(left, right, viewer), coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `server shutdown rolls back preparations and drops stale return points`() {
        val gateway = RecordingGateway().apply { unavailableForRestore += viewer }
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)
        assertTrue(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))

        coordinator.shutdown()

        assertTrue(coordinator.activeRoomIds().isEmpty())
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
        assertTrue(pool.activeLeases().isEmpty())
    }

    private fun room(
        spectators: List<UUID> = listOf(viewer),
        phase: PvpRoomPhase = PvpRoomPhase.ACTIVE,
    ) = PvpRoomView(
        roomId = roomId,
        hostId = left,
        settings = PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.SINGLE, PvpBattleMechanic.entries.toSet()),
        phase = phase,
        leftPlayerId = left,
        rightPlayerId = right,
        spectatorIds = spectators,
    )

    private class RecordingGateway : PvpLoungeGateway {
        val ensured = ArrayList<PvpArenaLease>()
        val competitors = ArrayList<Pair<UUID, PvpRoomSide>>()
        val spectators = ArrayList<UUID>()
        val spectating = ArrayList<Pair<UUID, UUID>>()
        val shownArenaHolograms = ArrayList<ArenaHologramEvent>()
        val hiddenArenaHolograms = ArrayList<Pair<UUID, UUID>>()
        val stoppedSpectating = ArrayList<Pair<UUID, UUID>>()
        val disconnectedSpectators = ArrayList<Pair<UUID, UUID>>()
        val restored = ArrayList<UUID>()
        val unavailableForRestore = LinkedHashSet<UUID>()
        var failDisconnect = false

        override fun ensureArena(lease: PvpArenaLease): Boolean = true.also { ensured += lease }

        override fun capture(playerId: UUID) = PvpReturnPoint("minecraft:overworld", playerId.leastSignificantBits.toDouble(), 64.0, 0.0, 0f, 0f, "survival")

        override fun moveCompetitor(playerId: UUID, lease: PvpArenaLease, side: PvpRoomSide): Boolean =
            true.also { competitors += playerId to side }

        override fun moveSpectator(playerId: UUID, lease: PvpArenaLease): Boolean =
            true.also { spectators += playerId }

        override fun spectate(viewerId: UUID, targetId: UUID): Boolean = true.also { spectating += viewerId to targetId }

        override fun showArenaHologram(playerId: UUID, battleId: UUID, lease: PvpArenaLease, perspective: PvpRoomSide) {
            shownArenaHolograms += ArenaHologramEvent(playerId, battleId, perspective)
        }

        override fun hideArenaHologram(playerId: UUID, battleId: UUID) {
            hiddenArenaHolograms += playerId to battleId
        }

        override fun stopSpectating(viewerId: UUID, battleId: UUID) {
            stoppedSpectating += viewerId to battleId
        }

        override fun disconnectSpectating(viewerId: UUID, battleId: UUID) {
            if (failDisconnect) error("forced disconnect cleanup failure")
            disconnectedSpectators += viewerId to battleId
        }

        override fun restore(playerId: UUID, point: PvpReturnPoint): Boolean {
            if (playerId in unavailableForRestore) return false
            restored += playerId
            return true
        }
    }

    private data class ArenaHologramEvent(
        val playerId: UUID,
        val battleId: UUID,
        val perspective: PvpRoomSide,
    )
}
