package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

/** Places room competitors before Cobblemon captures actor positions. */
internal class PvpRoomBattlePlacement(
    private val rooms: PvpRoomService,
    private val lounge: PvpLoungeCoordinator,
) : PvpBattlePlacement {
    override fun prepare(request: PvpBattleLaunchRequest): PvpPreparedBattlePlacement? {
        val room = rooms.get(request.matchId) ?: return DirectChallengePlacement
        if (room.phase != PvpRoomPhase.TEAM_PREVIEW) return null
        if (setOf(room.leftPlayerId, room.rightPlayerId) != setOf(request.firstPlayerId, request.secondPlayerId)) {
            return null
        }
        if (!lounge.prepare(room)) return null
        return RoomPlacement(request.matchId, rooms, lounge)
    }

    private class RoomPlacement(
        private val roomId: UUID,
        private val rooms: PvpRoomService,
        private val lounge: PvpLoungeCoordinator,
    ) : PvpPreparedBattlePlacement {
        override fun activate(startedBattleId: UUID): Boolean {
            if (!lounge.activate(roomId, startedBattleId)) return false
            if (rooms.markActive(roomId) is PvpRoomMutation.Applied) return true
            lounge.finish(roomId)
            return false
        }

        override fun rollback() {
            lounge.rollbackPreparation(roomId)
        }
    }

    private object DirectChallengePlacement : PvpPreparedBattlePlacement {
        override fun activate(startedBattleId: UUID): Boolean = true
        override fun rollback() = Unit
    }
}
