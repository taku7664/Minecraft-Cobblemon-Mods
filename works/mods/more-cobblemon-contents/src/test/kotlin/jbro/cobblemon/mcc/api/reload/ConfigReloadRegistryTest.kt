package jbro.cobblemon.mcc.api.reload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfigReloadRegistryTest {
    @Test
    fun `every reloader runs in order and a failing one is reported under its name without stopping the rest`() {
        val reported = mutableListOf<String>()
        val registry = ConfigReloadRegistry<String> { name, _ -> reported += name }
        registry.register("hub_tabs.json") { server -> listOf(MccReloadResult.Applied("hub_tabs.json", listOf(server))) }
        registry.register("addon.json") { error("unreadable") }
        registry.register("bp-shop.json") { listOf(MccReloadResult.Kept("bp-shop.json", "entries[0]: bad price")) }

        assertEquals(
            listOf(
                MccReloadResult.Applied("hub_tabs.json", listOf("server")),
                MccReloadResult.Kept("addon.json", "unreadable"),
                MccReloadResult.Kept("bp-shop.json", "entries[0]: bad price"),
            ),
            registry.reloadAll("server"),
        )
        assertEquals(listOf("addon.json"), reported)
    }

    @Test
    fun `a closed reloader no longer runs and a reloader needs a name`() {
        val registry = ConfigReloadRegistry<String>()
        registry.register("kept.json") { listOf(MccReloadResult.Applied("kept.json")) }
        registry.register("gone.json") { listOf(MccReloadResult.Applied("gone.json")) }.close()
        assertEquals(listOf(MccReloadResult.Applied("kept.json")), registry.reloadAll("server"))
        assertThrows(IllegalArgumentException::class.java) { registry.register(" ") { emptyList() } }
    }
}
