package jbro.cobblemon.morebattlecontent.client

import java.nio.file.Files
import java.nio.file.Path
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
    fun `all server scoped client state uses the shared reset boundary`() {
        val root = Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client")
        val initializer = Files.readString(root.resolve("MoreBattleContentClient.kt"))
        val consumers = listOf(
            "ShadowTerrainHologramRenderer.kt",
            "ShadowTrainerProjectionRenderer.kt",
            "PvpRoomHudOverlay.kt",
            "PvpLoungeSpectatorControls.kt",
            "ManagedBattleMechanicVisibility.kt",
            "BattleHubClientNetworking.kt",
            "ShopPlayClientNetworking.kt",
        ).associateWith { name -> Files.readString(root.resolve(name)) }

        assertTrue(initializer.contains("MbcClientSessionReset.registerEvents()"))
        consumers.forEach { (name, source) ->
            assertTrue(source.contains("MbcClientSessionReset.onReset"), "$name must join the shared reset boundary")
        }
    }
}
