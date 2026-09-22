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
    fun `spectator admission exception restores the captured player and leaves no partial membership`() {
        val gateway = RecordingGateway().apply {
            spectatorFailure = NoSuchMethodError("spectator API drift")
        }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(spectators = emptyList()), UUID(0, 900))

        assertFalse(coordinator.addSpectator(roomId, viewer, left))

        assertEquals(listOf(viewer), gateway.restored)
        assertEquals(listOf(viewer to UUID(0, 900)), gateway.stoppedSpectating)
        assertFalse(viewer in coordinator.pendingReturnPlayerIds())
        assertFalse(coordinator.removeSpectator(roomId, viewer))
    }

    @Test
    fun `failed spectator rollback keeps the captured return point retryable`() {
        val gateway = RecordingGateway().apply {
            spectatorFailure = IllegalStateException("spectator move failed")
            unavailableForRestore += viewer
        }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(spectators = emptyList()), UUID(0, 900))

        assertFalse(coordinator.addSpectator(roomId, viewer, left))

        assertTrue(viewer in coordinator.pendingReturnPlayerIds())
        gateway.unavailableForRestore.clear()
        assertTrue(coordinator.restorePending(viewer))
        assertFalse(viewer in coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `partial spectate and failed stop stay queued until both cleanup steps succeed`() {
        val gateway = RecordingGateway().apply {
            spectateFailure = NoSuchMethodError("spectate failed after registration")
            stopFailure = true
        }
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(spectators = emptyList()), UUID(0, 900))

        assertFalse(coordinator.addSpectator(roomId, viewer, left))

        assertTrue(viewer in coordinator.pendingReturnPlayerIds())
        assertTrue(gateway.restored.isEmpty())
        gateway.spectateFailure = null
        gateway.stopFailure = false
        assertTrue(coordinator.restorePending(viewer))
        assertEquals(listOf(viewer), gateway.restored)
        assertFalse(viewer in coordinator.pendingReturnPlayerIds())
    }

    @Test
    fun `reentrant room finish during spectator admission rolls back the detached viewer`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(spectators = emptyList()), UUID(0, 900))
        gateway.onSpectate = { coordinator.finish(roomId) }

        assertFalse(coordinator.addSpectator(roomId, viewer, left))

        assertTrue(coordinator.activeRoomIds().isEmpty())
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
        assertEquals(setOf(left, right, viewer), gateway.restored.toSet())
        assertFalse(coordinator.removeSpectator(roomId, viewer))
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
    fun `spectator exit waits to restore until failed spectating cleanup can retry`() {
        val gateway = RecordingGateway()
        val coordinator = PvpLoungeCoordinator(PvpArenaPool(), gateway)
        coordinator.start(room(), UUID(0, 900))
        gateway.stopFailure = true

        assertTrue(coordinator.removeSpectator(roomId, viewer))

        assertTrue(viewer in coordinator.pendingReturnPlayerIds())
        assertFalse(viewer in gateway.restored)
        gateway.stopFailure = false
        assertTrue(coordinator.restorePending(viewer))
        assertTrue(viewer in gateway.restored)
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
    fun `prepare exception restores captured players and releases the arena`() {
        val gateway = RecordingGateway().apply {
            competitorFailure = right to NoSuchMethodError("gateway API drift")
        }
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)

        assertFalse(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))

        assertEquals(setOf(left, right, viewer), gateway.restored.toSet())
        assertNull(pool.leaseFor(roomId))
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
    }

    @Test
    fun `prepare rollback keeps a failed restore queued for retry`() {
        val gateway = RecordingGateway().apply {
            competitorFailure = right to IllegalStateException("move failed")
            unavailableForRestore += left
        }
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)

        assertFalse(coordinator.prepare(room(phase = PvpRoomPhase.TEAM_PREVIEW)))

        assertEquals(setOf(left), coordinator.pendingReturnPlayerIds())
        assertNull(pool.leaseFor(roomId))
        gateway.unavailableForRestore.clear()
        assertTrue(coordinator.restorePending(left))
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
    }

    @Test
    fun `finish linkage failure cannot block player restore or arena release`() {
        val gateway = RecordingGateway()
        val pool = PvpArenaPool()
        val coordinator = PvpLoungeCoordinator(pool, gateway)
        assertTrue(coordinator.start(room(), UUID(0, 900)))
        gateway.hideFailure = NoSuchMethodError("hologram API drift")

        assertTrue(coordinator.finish(roomId))

        assertEquals(setOf(left, right, viewer), gateway.restored.toSet())
        assertTrue(coordinator.pendingReturnPlayerIds().isEmpty())
        assertNull(pool.leaseFor(roomId))
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
        var competitorFailure: Pair<UUID, Throwable>? = null
        var spectatorFailure: Throwable? = null
        var spectateFailure: Throwable? = null
        var stopFailure = false
        var onSpectate: (() -> Unit)? = null
        var hideFailure: Throwable? = null

        override fun ensureArena(lease: PvpArenaLease): Boolean = true.also { ensured += lease }

        override fun capture(playerId: UUID) = PvpReturnPoint("minecraft:overworld", playerId.leastSignificantBits.toDouble(), 64.0, 0.0, 0f, 0f, "survival")

        override fun moveCompetitor(playerId: UUID, lease: PvpArenaLease, side: PvpRoomSide): Boolean {
            competitorFailure?.takeIf { it.first == playerId }?.second?.let { throw it }
            competitors += playerId to side
            return true
        }

        override fun moveSpectator(playerId: UUID, lease: PvpArenaLease): Boolean {
            spectatorFailure?.let { throw it }
            spectators += playerId
            return true
        }

        override fun spectate(viewerId: UUID, targetId: UUID): Boolean {
            spectating += viewerId to targetId
            onSpectate?.invoke()
            spectateFailure?.let { throw it }
            return true
        }

        override fun showArenaHologram(playerId: UUID, battleId: UUID, lease: PvpArenaLease, perspective: PvpRoomSide) {
            shownArenaHolograms += ArenaHologramEvent(playerId, battleId, perspective)
        }

        override fun hideArenaHologram(playerId: UUID, battleId: UUID) {
            hideFailure?.let { throw it }
            hiddenArenaHolograms += playerId to battleId
        }

        override fun stopSpectating(viewerId: UUID, battleId: UUID) {
            if (stopFailure) error("forced stop failure")
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
