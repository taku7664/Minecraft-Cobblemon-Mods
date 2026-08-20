package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections
import java.util.UUID

internal enum class PvpBattleMechanic(val id: String) {
    MEGA("mega"),
    DYNAMAX("dynamax"),
    TERA("tera"),
    Z_MOVE("z_move"),
}

internal object PvpRoomDefaults {
    val ENABLED_MECHANICS: Set<PvpBattleMechanic> = setOf(PvpBattleMechanic.MEGA)
}

internal enum class PvpRoomVisibility {
    PUBLIC,
    PRIVATE,
}

internal enum class PvpRoomSide {
    LEFT,
    RIGHT,
}

internal enum class PvpRoomPhase {
    LOBBY,
    TEAM_PREVIEW,
    ACTIVE,
    CLOSED,
}

internal data class PvpRoomSettings(
    val visibility: PvpRoomVisibility,
    val format: PvpBattleFormat,
    val enabledMechanics: Set<PvpBattleMechanic>,
) {
    val immutableEnabledMechanics: Set<PvpBattleMechanic> =
        Collections.unmodifiableSet(LinkedHashSet(enabledMechanics))
}

internal data class PvpRoomView(
    val roomId: UUID,
    val hostId: UUID,
    val settings: PvpRoomSettings,
    val phase: PvpRoomPhase,
    val leftPlayerId: UUID?,
    val rightPlayerId: UUID?,
    val spectatorIds: List<UUID>,
) {
    val immutableSpectatorIds: List<UUID> = Collections.unmodifiableList(ArrayList(spectatorIds))

    val memberIds: Set<UUID>
        get() = buildSet {
            leftPlayerId?.let(::add)
            rightPlayerId?.let(::add)
            addAll(spectatorIds)
        }
}

internal enum class PvpRoomError {
    UNKNOWN_ROOM,
    PLAYER_BUSY,
    ALREADY_MEMBER,
    INVITE_REQUIRED,
    NOT_MEMBER,
    HOST_ONLY,
    TARGET_NOT_MEMBER,
    SEAT_OCCUPIED,
    SEATS_INCOMPLETE,
    INVALID_PHASE,
}

internal sealed interface PvpRoomMutation {
    data class Applied(val room: PvpRoomView) : PvpRoomMutation
    data class Rejected(val error: PvpRoomError) : PvpRoomMutation
}

internal fun PvpRoomMutation.errorOrNull(): PvpRoomError? = (this as? PvpRoomMutation.Rejected)?.error

internal class PvpRoomService(
    private val roomIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val rooms = LinkedHashMap<UUID, MutableRoom>()
    private val roomByPlayer = HashMap<UUID, UUID>()
    private var joinSequence = 0L

    @Synchronized
    fun create(hostId: UUID, settings: PvpRoomSettings): PvpRoomMutation.Applied {
        roomByPlayer[hostId]?.let { existingId ->
            rooms[existingId]?.let { return PvpRoomMutation.Applied(it.view()) }
            roomByPlayer.remove(hostId)
        }
        var roomId = roomIdFactory()
        while (roomId in rooms) roomId = roomIdFactory()
        val room = MutableRoom(roomId, hostId, settings.copy(enabledMechanics = settings.immutableEnabledMechanics))
        room.members[hostId] = ++joinSequence
        rooms[roomId] = room
        roomByPlayer[hostId] = roomId
        return PvpRoomMutation.Applied(room.view())
    }

    @Synchronized
    fun get(roomId: UUID): PvpRoomView? = rooms[roomId]?.view()

    @Synchronized
    fun roomFor(playerId: UUID): PvpRoomView? = roomByPlayer[playerId]?.let(rooms::get)?.view()

    @Synchronized
    fun publicRooms(): List<PvpRoomView> = rooms.values
        .asSequence()
        .filter { it.settings.visibility == PvpRoomVisibility.PUBLIC && it.phase != PvpRoomPhase.CLOSED }
        .map(MutableRoom::view)
        .toList()

    @Synchronized
    fun visibleRoomsFor(playerId: UUID): List<PvpRoomView> = rooms.values
        .asSequence()
        .filter { room ->
            room.phase != PvpRoomPhase.CLOSED &&
                (room.settings.visibility == PvpRoomVisibility.PUBLIC || playerId in room.invited)
        }
        .map(MutableRoom::view)
        .toList()

    @Synchronized
    fun invite(roomId: UUID, actorId: UUID, targetId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (room.hostId != actorId) return rejected(PvpRoomError.HOST_ONLY)
        if (room.phase == PvpRoomPhase.CLOSED) return rejected(PvpRoomError.INVALID_PHASE)
        room.invited += targetId
        return applied(room)
    }

    @Synchronized
    fun declineInvite(roomId: UUID, playerId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (!room.invited.remove(playerId)) return rejected(PvpRoomError.INVITE_REQUIRED)
        return applied(room)
    }

    @Synchronized
    fun join(roomId: UUID, playerId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (playerId in room.members) return applied(room)
        if (playerId in roomByPlayer) return rejected(PvpRoomError.PLAYER_BUSY)
        if (room.phase == PvpRoomPhase.CLOSED) return rejected(PvpRoomError.INVALID_PHASE)
        if (room.settings.visibility == PvpRoomVisibility.PRIVATE && playerId !in room.invited) {
            return rejected(PvpRoomError.INVITE_REQUIRED)
        }
        room.members[playerId] = ++joinSequence
        roomByPlayer[playerId] = roomId
        return applied(room)
    }

    @Synchronized
    fun claimSeat(roomId: UUID, playerId: UUID, side: PvpRoomSide): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (playerId !in room.members) return rejected(PvpRoomError.NOT_MEMBER)
        if (room.phase != PvpRoomPhase.LOBBY) return rejected(PvpRoomError.INVALID_PHASE)
        val occupant = room.seat(side)
        if (occupant != null && occupant != playerId) return rejected(PvpRoomError.SEAT_OCCUPIED)
        room.clearSeat(playerId)
        room.setSeat(side, playerId)
        return applied(room)
    }

    @Synchronized
    fun observe(roomId: UUID, playerId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (playerId !in room.members) return rejected(PvpRoomError.NOT_MEMBER)
        if (room.phase != PvpRoomPhase.LOBBY) return rejected(PvpRoomError.INVALID_PHASE)
        room.clearSeat(playerId)
        return applied(room)
    }

    @Synchronized
    fun updateSettings(roomId: UUID, actorId: UUID, settings: PvpRoomSettings): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (room.hostId != actorId) return rejected(PvpRoomError.HOST_ONLY)
        if (room.phase != PvpRoomPhase.LOBBY) return rejected(PvpRoomError.INVALID_PHASE)
        room.settings = settings.copy(enabledMechanics = settings.immutableEnabledMechanics)
        return applied(room)
    }

    @Synchronized
    fun transferHost(roomId: UUID, actorId: UUID, targetId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (room.hostId != actorId) return rejected(PvpRoomError.HOST_ONLY)
        if (targetId !in room.members) return rejected(PvpRoomError.TARGET_NOT_MEMBER)
        room.hostId = targetId
        return applied(room)
    }

    @Synchronized
    fun startPreview(roomId: UUID, actorId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (room.hostId != actorId) return rejected(PvpRoomError.HOST_ONLY)
        if (room.phase != PvpRoomPhase.LOBBY) return rejected(PvpRoomError.INVALID_PHASE)
        if (room.leftPlayerId == null || room.rightPlayerId == null) return rejected(PvpRoomError.SEATS_INCOMPLETE)
        room.phase = PvpRoomPhase.TEAM_PREVIEW
        return applied(room)
    }

    @Synchronized
    fun markActive(roomId: UUID): PvpRoomMutation {
        val room = rooms[roomId] ?: return rejected(PvpRoomError.UNKNOWN_ROOM)
        if (room.phase != PvpRoomPhase.TEAM_PREVIEW) return rejected(PvpRoomError.INVALID_PHASE)
        room.phase = PvpRoomPhase.ACTIVE
        return applied(room)
    }

    /**
     * Returns a room to its lobby once the match it hosted is over. Seats, members, invites and the
     * player-to-room index all survive so the same group can immediately rematch, which is what
     * separates this from [close].
     */
    @Synchronized
    fun finishMatch(roomId: UUID): PvpRoomView? {
        val room = rooms[roomId] ?: return null
        if (room.phase == PvpRoomPhase.CLOSED) return null
        room.phase = PvpRoomPhase.LOBBY
        return room.view()
    }

    @Synchronized
    fun close(roomId: UUID): PvpRoomView? {
        val room = rooms[roomId] ?: return null
        room.phase = PvpRoomPhase.CLOSED
        val closed = room.view()
        rooms.remove(roomId)
        room.members.keys.forEach { playerId -> roomByPlayer.remove(playerId, roomId) }
        return closed
    }

    @Synchronized
    fun leave(roomId: UUID, playerId: UUID): PvpRoomView? {
        val room = rooms[roomId] ?: return null
        if (room.members.remove(playerId) == null) return room.view()
        roomByPlayer.remove(playerId, roomId)
        room.invited -= playerId
        room.clearSeat(playerId)
        if (room.members.isEmpty()) {
            rooms.remove(roomId)
            return null
        }
        if (room.hostId == playerId) {
            room.hostId = room.members.minBy { it.value }.key
        }
        if (room.phase != PvpRoomPhase.LOBBY && (room.leftPlayerId == null || room.rightPlayerId == null)) {
            room.phase = PvpRoomPhase.CLOSED
        }
        return room.view()
    }

    private fun applied(room: MutableRoom) = PvpRoomMutation.Applied(room.view())
    private fun rejected(error: PvpRoomError) = PvpRoomMutation.Rejected(error)

    private class MutableRoom(
        val roomId: UUID,
        var hostId: UUID,
        var settings: PvpRoomSettings,
    ) {
        var phase = PvpRoomPhase.LOBBY
        var leftPlayerId: UUID? = null
        var rightPlayerId: UUID? = null
        val members = LinkedHashMap<UUID, Long>()
        val invited = LinkedHashSet<UUID>()

        fun seat(side: PvpRoomSide): UUID? = if (side == PvpRoomSide.LEFT) leftPlayerId else rightPlayerId

        fun setSeat(side: PvpRoomSide, playerId: UUID) {
            if (side == PvpRoomSide.LEFT) leftPlayerId = playerId else rightPlayerId = playerId
        }

        fun clearSeat(playerId: UUID) {
            if (leftPlayerId == playerId) leftPlayerId = null
            if (rightPlayerId == playerId) rightPlayerId = null
        }

        fun view() = PvpRoomView(
            roomId = roomId,
            hostId = hostId,
            settings = settings,
            phase = phase,
            leftPlayerId = leftPlayerId,
            rightPlayerId = rightPlayerId,
            spectatorIds = members.keys.filter { it != leftPlayerId && it != rightPlayerId },
        )
    }
}
