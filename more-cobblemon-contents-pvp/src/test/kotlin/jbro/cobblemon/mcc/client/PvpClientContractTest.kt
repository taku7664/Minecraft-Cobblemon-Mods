package jbro.cobblemon.mcc.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomSummaryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpClientContractTest {
    @Test
    fun `pvp client state uses the shared reset boundary`() {
        val root = Path.of("src/main/kotlin/jbro/cobblemon/mcc/client")
        listOf("PvpRoomHudOverlay.kt", "PvpLoungeSpectatorControls.kt", "PvpPlayClientNetworking.kt").forEach { name ->
            assertTrue(Files.readString(root.resolve(name)).contains("MccClientSessionReset.onReset"), "$name must join the shared reset boundary")
        }
    }

    @Test
    fun `pvp room state reset removes previous server rooms and pending navigation`() {
        val roomId = UUID.randomUUID()
        val hostId = UUID.randomUUID()
        val settings = PvpRoomSettings(
            PvpRoomVisibility.PUBLIC,
            PvpBattleFormat.SINGLE,
            setOf(PvpBattleMechanic.MEGA),
        )
        val host = PvpRoomMemberView(hostId, "host")
        PvpRoomClientState.lastRooms = listOf(
            PvpRoomSummaryView(roomId, host, settings, PvpRoomPhase.LOBBY, host, null, 0),
        )
        PvpRoomClientState.lastRoom = PvpRoomClientView(
            roomId,
            hostId,
            settings,
            PvpRoomPhase.LOBBY,
            host,
            null,
            emptyList(),
            emptyList(),
        )
        PvpRoomClientState.pendingOpenRequests += UUID.randomUUID()

        PvpRoomClientState.clear()

        assertTrue(PvpRoomClientState.lastRooms.isEmpty())
        assertEquals(null, PvpRoomClientState.lastRoom)
        assertTrue(PvpRoomClientState.pendingOpenRequests.isEmpty())
    }
}
