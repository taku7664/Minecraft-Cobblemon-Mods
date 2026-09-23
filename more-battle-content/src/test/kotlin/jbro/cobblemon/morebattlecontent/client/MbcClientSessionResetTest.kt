package jbro.cobblemon.morebattlecontent.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomSummaryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MbcClientSessionResetTest {
    @Test
    fun `every reset callback runs on every session boundary`() {
        val registry = ClientSessionResetRegistry()
        val calls = mutableListOf<String>()
        registry.add("terrain") { calls += "terrain" }
        registry.add("trainer") { calls += "trainer" }

        registry.resetAll { _, failure -> throw failure }
        registry.resetAll { _, failure -> throw failure }

        assertEquals(listOf("terrain", "trainer", "terrain", "trainer"), calls)
    }

    @Test
    fun `one broken reset cannot prevent the remaining state from clearing`() {
        val registry = ClientSessionResetRegistry()
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        registry.add("broken") { error("reset failed") }
        registry.add("healthy") { calls += "healthy" }

        registry.resetAll { name, _ -> failures += name }

        assertEquals(listOf("broken"), failures)
        assertEquals(listOf("healthy"), calls)
    }

    @Test
    fun `compatibility and reporter failures cannot block later resets`() {
        val registry = ClientSessionResetRegistry()
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        registry.add("compatibility") { throw NoSuchMethodError("client API drift") }
        registry.add("runtime") { error("reset failed") }
        registry.add("healthy") { calls += "healthy" }

        registry.resetAll { name, _ ->
            failures += name
            if (name == "compatibility") throw NoSuchMethodError("logger API drift")
        }

        assertEquals(listOf("compatibility", "runtime"), failures)
        assertEquals(listOf("healthy"), calls)
    }

    @Test
    fun `all server scoped client state uses the shared reset boundary`() {
        val root = Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client")
        val initializer = Files.readString(root.resolve("MoreBattleContentClient.kt"))
        val consumers = listOf(
            "ShadowTerrainHologramRenderer.kt",
            "ShadowTrainerProjectionRenderer.kt",
            "PvpRoomHudOverlay.kt",
            "PvpLoungeSpectatorControls.kt",
            "PvpPlayClientNetworking.kt",
            "ManagedBattleMechanicVisibility.kt",
            "BattleHubClientNetworking.kt",
            "ShopPlayClientNetworking.kt",
        ).associateWith { name -> Files.readString(root.resolve(name)) }

        assertTrue(initializer.contains("MbcClientSessionReset.registerEvents()"))
        consumers.forEach { (name, source) ->
            assertTrue(source.contains("MbcClientSessionReset.onReset"), "$name must join the shared reset boundary")
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
