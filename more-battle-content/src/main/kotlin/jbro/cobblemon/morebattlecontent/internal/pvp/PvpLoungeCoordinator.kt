package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely

internal data class PvpReturnPoint(
    val dimensionId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val gameModeId: String,
)

internal interface PvpLoungeGateway {
    fun ensureArena(lease: PvpArenaLease): Boolean
    fun capture(playerId: UUID): PvpReturnPoint?
    fun moveCompetitor(playerId: UUID, lease: PvpArenaLease, side: PvpRoomSide): Boolean
    fun moveSpectator(playerId: UUID, lease: PvpArenaLease): Boolean
    fun spectate(viewerId: UUID, targetId: UUID): Boolean
    fun stopSpectating(viewerId: UUID, battleId: UUID) = Unit
    fun disconnectSpectating(viewerId: UUID, battleId: UUID) = Unit
    fun showArenaHologram(playerId: UUID, battleId: UUID, lease: PvpArenaLease, perspective: PvpRoomSide) = Unit
    fun hideArenaHologram(playerId: UUID, battleId: UUID) = Unit
    fun restore(playerId: UUID, point: PvpReturnPoint): Boolean
}

internal class PvpLoungeCoordinator(
    private val arenas: PvpArenaPool,
    private val gateway: PvpLoungeGateway,
) {
    private val sessions = LinkedHashMap<UUID, Session>()
    private val preparations = LinkedHashMap<UUID, Preparation>()
    private val returns = LinkedHashMap<UUID, PvpReturnPoint>()
    private val pendingSpectatorStops = LinkedHashMap<UUID, UUID>()

    @Synchronized
    fun start(room: PvpRoomView, battleId: UUID): Boolean {
        if (room.phase != PvpRoomPhase.ACTIVE) return false
        if (!prepare(room.copy(phase = PvpRoomPhase.TEAM_PREVIEW))) return false
        return activate(room.roomId, battleId)
    }

    @Synchronized
    fun prepare(room: PvpRoomView): Boolean {
        if (room.roomId in sessions || room.roomId in preparations || room.phase != PvpRoomPhase.TEAM_PREVIEW) {
            return false
        }
        val left = room.leftPlayerId ?: return false
        val right = room.rightPlayerId ?: return false
        val players = listOf(left, right) + room.spectatorIds
        if (players.distinct().size != players.size || players.any(::hasPendingPlayerCleanup)) return false
        val lease = arenas.acquire(room.roomId)
        val captured = LinkedHashMap<UUID, PvpReturnPoint>()
        try {
            if (!gateway.ensureArena(lease)) {
                arenas.release(room.roomId)
                return false
            }
            players.forEach { playerId ->
                val point = gateway.capture(playerId) ?: return rollbackCaptured(room.roomId, captured)
                captured[playerId] = point
            }
            returns.putAll(captured)
            if (!gateway.moveCompetitor(left, lease, PvpRoomSide.LEFT) ||
                !gateway.moveCompetitor(right, lease, PvpRoomSide.RIGHT)
            ) {
                return rollbackCaptured(room.roomId, captured)
            }
            preparations[room.roomId] = Preparation(
                lease = lease,
                leftPlayerId = left,
                rightPlayerId = right,
                spectators = room.spectatorIds.toMutableSet(),
                capturedPlayerIds = captured.keys.toSet(),
            )
            return true
        } catch (failure: Throwable) {
            if (failure !is RuntimeException && failure !is LinkageError) throw failure
            MoreBattleContent.LOGGER.error("PvP lounge preparation failed for room ${room.roomId}", failure)
            return rollbackCaptured(room.roomId, captured)
        }
    }

    @Synchronized
    fun activate(roomId: UUID, battleId: UUID): Boolean {
        val preparation = preparations[roomId] ?: return false
        val attemptedSpectators = LinkedHashSet<UUID>()
        preparation.spectators.forEach { viewerId ->
            attemptedSpectators += viewerId
            // Mark the stop obligation before calling external code. The gateway can synchronously
            // re-enter rollbackPreparation, which must not restore the player ahead of this stop.
            pendingSpectatorStops[viewerId] = battleId
            var moved = false
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("PvP spectator move failed for $viewerId", failure)
                },
                { moved = gateway.moveSpectator(viewerId, preparation.lease) },
            )
            if (!moved || preparations[roomId] !== preparation) {
                rollbackActivation(roomId, battleId, attemptedSpectators)
                return false
            }
            var spectating = false
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("PvP spectator registration failed for $viewerId", failure)
                },
                { spectating = gateway.spectate(viewerId, preparation.leftPlayerId) },
            )
            if (!spectating || preparations[roomId] !== preparation) {
                rollbackActivation(roomId, battleId, attemptedSpectators)
                return false
            }
        }
        if (preparations[roomId] !== preparation) {
            rollbackActivation(roomId, battleId, attemptedSpectators)
            return false
        }
        preparations.remove(roomId)
        sessions[roomId] = Session(
            battleId,
            preparation.lease,
            preparation.leftPlayerId,
            preparation.rightPlayerId,
            preparation.spectators,
        )
        attemptedSpectators.forEach(pendingSpectatorStops::remove)
        runGatewayCleanup("show arena hologram", preparation.leftPlayerId) {
            gateway.showArenaHologram(preparation.leftPlayerId, battleId, preparation.lease, PvpRoomSide.LEFT)
        }
        runGatewayCleanup("show arena hologram", preparation.rightPlayerId) {
            gateway.showArenaHologram(preparation.rightPlayerId, battleId, preparation.lease, PvpRoomSide.RIGHT)
        }
        preparation.spectators.forEach { viewerId ->
            runGatewayCleanup("show spectator arena hologram", viewerId) {
                gateway.showArenaHologram(viewerId, battleId, preparation.lease, PvpRoomSide.LEFT)
            }
        }
        return true
    }

    @Synchronized
    fun rollbackPreparation(roomId: UUID): Boolean {
        val preparation = preparations.remove(roomId) ?: return false
        preparation.capturedPlayerIds.forEach(::restorePending)
        arenas.release(roomId)
        return true
    }

    private fun rollbackActivation(roomId: UUID, battleId: UUID, attemptedSpectators: Set<UUID>) {
        attemptedSpectators.forEach { playerId ->
            val stopped = tryGatewayCleanup("stop partial battle spectating", playerId) {
                gateway.stopSpectating(playerId, battleId)
            }
            if (stopped) {
                pendingSpectatorStops.remove(playerId)
            } else {
                pendingSpectatorStops[playerId] = battleId
            }
        }
        rollbackPreparation(roomId)
    }

    @Synchronized
    fun addSpectator(roomId: UUID, playerId: UUID, targetId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (playerId in session.spectators) return true
        if (hasPendingPlayerCleanup(playerId)) return false
        if (targetId != session.leftPlayerId && targetId != session.rightPlayerId) return false
        val point = try {
            gateway.capture(playerId)
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Could not capture PvP spectator return point for $playerId", failure)
            return false
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Could not capture PvP spectator return point for $playerId", failure)
            return false
        } ?: return false
        returns[playerId] = point
        if (sessions[roomId] !== session) {
            if (restorePoint(playerId, point)) returns.remove(playerId)
            return false
        }
        try {
            if (!gateway.moveSpectator(playerId, session.lease)) {
                return rollbackSpectatorAdmission(playerId, session, point)
            }
            if (sessions[roomId] !== session) return rollbackSpectatorAdmission(playerId, session, point)
            if (!gateway.spectate(playerId, targetId)) return rollbackSpectatorAdmission(playerId, session, point)
            if (sessions[roomId] !== session) return rollbackSpectatorAdmission(playerId, session, point)
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("PvP spectator admission failed for $playerId", failure)
            return rollbackSpectatorAdmission(playerId, session, point)
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("PvP spectator admission failed for $playerId", failure)
            return rollbackSpectatorAdmission(playerId, session, point)
        }
        session.spectators += playerId
        val perspective = if (targetId == session.rightPlayerId) PvpRoomSide.RIGHT else PvpRoomSide.LEFT
        runGatewayCleanup("show spectator arena hologram", playerId) {
            gateway.showArenaHologram(playerId, session.battleId, session.lease, perspective)
        }
        return true
    }

    @Synchronized
    fun removeSpectator(roomId: UUID, playerId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (!session.spectators.remove(playerId)) return false
        runGatewayCleanup("hide spectator arena hologram", playerId) {
            gateway.hideArenaHologram(playerId, session.battleId)
        }
        val stopped = tryGatewayCleanup("stop battle spectating", playerId) {
            gateway.stopSpectating(playerId, session.battleId)
        }
        if (stopped) {
            restorePending(playerId)
        } else {
            pendingSpectatorStops[playerId] = session.battleId
        }
        return true
    }

    @Synchronized
    fun disconnectSpectator(roomId: UUID, playerId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (!session.spectators.remove(playerId)) return false
        val disconnected = tryGatewayCleanup("disconnect battle spectator", playerId) {
            gateway.disconnectSpectating(playerId, session.battleId)
        }
        if (!disconnected) pendingSpectatorStops[playerId] = session.battleId
        return true
    }

    @Synchronized
    fun finish(roomId: UUID): Boolean {
        val session = sessions.remove(roomId) ?: return rollbackPreparation(roomId)
        val players = linkedSetOf(session.leftPlayerId, session.rightPlayerId).apply {
            addAll(session.spectators)
            pendingSpectatorStops.forEach { (playerId, battleId) ->
                if (battleId == session.battleId) add(playerId)
            }
        }
        players.forEach { playerId ->
            runGatewayCleanup("hide arena hologram", playerId) {
                gateway.hideArenaHologram(playerId, session.battleId)
            }
        }
        session.spectators.forEach { playerId ->
            val stopped = tryGatewayCleanup("stop battle spectating", playerId) {
                gateway.stopSpectating(playerId, session.battleId)
            }
            if (!stopped) pendingSpectatorStops[playerId] = session.battleId
        }
        players.forEach(::restorePending)
        arenas.release(roomId)
        return true
    }

    @Synchronized
    fun restorePending(playerId: UUID): Boolean {
        val pendingBattleId = pendingSpectatorStops[playerId]
        var stoppedPendingSpectating = false
        if (pendingBattleId != null) {
            val stopped = tryGatewayCleanup("stop partial battle spectating", playerId) {
                gateway.stopSpectating(playerId, pendingBattleId)
            }
            if (!stopped) return false
            pendingSpectatorStops.remove(playerId)
            stoppedPendingSpectating = true
        }
        val point = returns[playerId] ?: return stoppedPendingSpectating
        if (!restorePoint(playerId, point)) return false
        returns.remove(playerId)
        return true
    }

    @Synchronized
    fun restoreAvailable(isAvailable: (UUID) -> Boolean): Set<UUID> {
        val currentlyPlaced = buildSet {
            preparations.values.forEach { preparation -> addAll(preparation.capturedPlayerIds) }
            sessions.values.forEach { session ->
                add(session.leftPlayerId)
                add(session.rightPlayerId)
                addAll(session.spectators)
            }
        }
        return (returns.keys + pendingSpectatorStops.keys)
            .toList()
            .asSequence()
            .filterNot(currentlyPlaced::contains)
            .filter(isAvailable)
            .filter(::restorePending)
            .toSet()
    }

    @Synchronized
    fun leaseFor(roomId: UUID): PvpArenaLease? = sessions[roomId]?.lease ?: preparations[roomId]?.lease

    @Synchronized
    fun pendingReturnPlayerIds(): Set<UUID> = (returns.keys + pendingSpectatorStops.keys).toSet()

    @Synchronized
    fun activeRoomIds(): Set<UUID> = sessions.keys.toSet()

    private fun hasPendingPlayerCleanup(playerId: UUID): Boolean =
        playerId in returns || playerId in pendingSpectatorStops

    /** Best-effort restoration followed by a hard state reset for an ending server instance. */
    @Synchronized
    fun shutdown() {
        (sessions.keys + preparations.keys).toSet().forEach(::finish)
        restoreAvailable { true }
        sessions.clear()
        preparations.clear()
        returns.clear()
        pendingSpectatorStops.clear()
    }

    private fun rollbackCaptured(roomId: UUID, captured: Map<UUID, PvpReturnPoint>): Boolean {
        captured.forEach { (playerId, point) -> returns.putIfAbsent(playerId, point) }
        captured.forEach { (playerId, point) ->
            if (restorePoint(playerId, point)) returns.remove(playerId)
        }
        arenas.release(roomId)
        return false
    }

    private fun rollbackSpectatorAdmission(playerId: UUID, session: Session, point: PvpReturnPoint): Boolean {
        val stopped = tryGatewayCleanup("stop partial battle spectating", playerId) {
            gateway.stopSpectating(playerId, session.battleId)
        }
        if (!stopped) {
            pendingSpectatorStops[playerId] = session.battleId
            return false
        }
        pendingSpectatorStops.remove(playerId)
        if (restorePoint(playerId, point)) returns.remove(playerId)
        return false
    }

    private fun restorePoint(playerId: UUID, point: PvpReturnPoint): Boolean {
        var restored = false
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("PvP lounge restore failed for $playerId", failure)
            },
            { restored = gateway.restore(playerId, point) },
        )
        return restored
    }

    private fun runGatewayCleanup(action: String, playerId: UUID, cleanup: () -> Unit) {
        tryGatewayCleanup(action, playerId, cleanup)
    }

    private fun tryGatewayCleanup(action: String, playerId: UUID, cleanup: () -> Unit): Boolean {
        var succeeded = false
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("Could not $action for $playerId", failure)
            },
            {
                cleanup()
                succeeded = true
            },
        )
        return succeeded
    }

    private data class Preparation(
        val lease: PvpArenaLease,
        val leftPlayerId: UUID,
        val rightPlayerId: UUID,
        val spectators: MutableSet<UUID>,
        val capturedPlayerIds: Set<UUID>,
    )

    private data class Session(
        val battleId: UUID,
        val lease: PvpArenaLease,
        val leftPlayerId: UUID,
        val rightPlayerId: UUID,
        val spectators: MutableSet<UUID>,
    )
}
