package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

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
        if (players.distinct().size != players.size || players.any { it in returns }) return false
        val lease = arenas.acquire(room.roomId)
        if (!gateway.ensureArena(lease)) {
            arenas.release(room.roomId)
            return false
        }
        val captured = LinkedHashMap<UUID, PvpReturnPoint>()
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
    }

    @Synchronized
    fun activate(roomId: UUID, battleId: UUID): Boolean {
        val preparation = preparations[roomId] ?: return false
        preparation.spectators.forEach { viewerId ->
            if (!gateway.moveSpectator(viewerId, preparation.lease) ||
                !gateway.spectate(viewerId, preparation.leftPlayerId)
            ) {
                rollbackPreparation(roomId)
                return false
            }
        }
        preparations.remove(roomId)
        sessions[roomId] = Session(
            battleId,
            preparation.lease,
            preparation.leftPlayerId,
            preparation.rightPlayerId,
            preparation.spectators,
        )
        gateway.showArenaHologram(preparation.leftPlayerId, battleId, preparation.lease, PvpRoomSide.LEFT)
        gateway.showArenaHologram(preparation.rightPlayerId, battleId, preparation.lease, PvpRoomSide.RIGHT)
        preparation.spectators.forEach { viewerId ->
            gateway.showArenaHologram(viewerId, battleId, preparation.lease, PvpRoomSide.LEFT)
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

    @Synchronized
    fun addSpectator(roomId: UUID, playerId: UUID, targetId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (playerId in session.spectators) return true
        if (playerId in returns) return false
        val point = gateway.capture(playerId) ?: return false
        returns[playerId] = point
        if (!gateway.moveSpectator(playerId, session.lease) || !gateway.spectate(playerId, targetId)) {
            if (gateway.restore(playerId, point)) returns.remove(playerId)
            return false
        }
        session.spectators += playerId
        val perspective = if (targetId == session.rightPlayerId) PvpRoomSide.RIGHT else PvpRoomSide.LEFT
        gateway.showArenaHologram(playerId, session.battleId, session.lease, perspective)
        return true
    }

    @Synchronized
    fun removeSpectator(roomId: UUID, playerId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (!session.spectators.remove(playerId)) return false
        gateway.hideArenaHologram(playerId, session.battleId)
        gateway.stopSpectating(playerId, session.battleId)
        restorePending(playerId)
        return true
    }

    @Synchronized
    fun disconnectSpectator(roomId: UUID, playerId: UUID): Boolean {
        val session = sessions[roomId] ?: return false
        if (!session.spectators.remove(playerId)) return false
        gateway.disconnectSpectating(playerId, session.battleId)
        return true
    }

    @Synchronized
    fun finish(roomId: UUID): Boolean {
        val session = sessions.remove(roomId) ?: return rollbackPreparation(roomId)
        val players = linkedSetOf(session.leftPlayerId, session.rightPlayerId).apply { addAll(session.spectators) }
        players.forEach { gateway.hideArenaHologram(it, session.battleId) }
        session.spectators.forEach { gateway.stopSpectating(it, session.battleId) }
        players.forEach(::restorePending)
        arenas.release(roomId)
        return true
    }

    @Synchronized
    fun restorePending(playerId: UUID): Boolean {
        val point = returns[playerId] ?: return false
        if (!gateway.restore(playerId, point)) return false
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
        return returns.keys
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
    fun pendingReturnPlayerIds(): Set<UUID> = returns.keys.toSet()

    @Synchronized
    fun activeRoomIds(): Set<UUID> = sessions.keys.toSet()

    private fun rollbackCaptured(roomId: UUID, captured: Map<UUID, PvpReturnPoint>): Boolean {
        captured.forEach { (playerId, point) ->
            if (gateway.restore(playerId, point)) returns.remove(playerId)
        }
        arenas.release(roomId)
        return false
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
