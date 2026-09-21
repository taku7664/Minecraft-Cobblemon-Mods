package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PokeFusionConfigStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `missing config is created with public command access`() {
        val config = requireNotNull(PokeFusionConfigStore(directory).load())

        assertEquals(0, config.commandPermissionLevel)
        val written = Files.readString(directory.resolve("pokefusion.json"))
        assertTrue(written.contains("\"commandPermissionLevel\": 0"))
    }

    @Test
    fun `configured permission level is loaded`() {
        Files.writeString(directory.resolve("pokefusion.json"), """{"commandPermissionLevel":3}""")

        assertEquals(3, requireNotNull(PokeFusionConfigStore(directory).load()).commandPermissionLevel)
    }

    @Test
    fun `out of range permission fails closed without overwriting user file`() {
        val path = directory.resolve("pokefusion.json")
        val invalid = """{"commandPermissionLevel":9}"""
        Files.writeString(path, invalid)

        assertEquals(4, PokeFusionConfigStore(directory).load().commandPermissionLevel)
        assertEquals(invalid, Files.readString(path))
    }

    @Test
    fun `malformed config fails closed without overwriting user file`() {
        val path = directory.resolve("pokefusion.json")
        val malformed = "{broken"
        Files.writeString(path, malformed)

        assertEquals(4, PokeFusionConfigStore(directory).load().commandPermissionLevel)
        assertEquals(malformed, Files.readString(path))
    }

    @Test
    fun `missing permission field fails closed`() {
        Files.writeString(directory.resolve("pokefusion.json"), "{}")

        assertEquals(4, PokeFusionConfigStore(directory).load().commandPermissionLevel)
    }

    @Test
    fun `non numeric permission field fails closed`() {
        Files.writeString(directory.resolve("pokefusion.json"), """{"commandPermissionLevel":"0"}""")

        assertEquals(4, PokeFusionConfigStore(directory).load().commandPermissionLevel)
    }

    @Test
    fun `fractional permission level fails closed instead of truncating`() {
        Files.writeString(directory.resolve("pokefusion.json"), """{"commandPermissionLevel":0.5}""")

        assertEquals(4, PokeFusionConfigStore(directory).load().commandPermissionLevel)
    }

    @Test
    fun `unreadable config location fails closed`() {
        val occupiedPath = directory.resolve("not-a-directory")
        Files.writeString(occupiedPath, "occupied")

        assertEquals(4, PokeFusionConfigStore(occupiedPath).load().commandPermissionLevel)
    }

    @Test
    fun `saved permission level is written atomically and can be reloaded`() {
        val store = PokeFusionConfigStore(directory)

        store.save(PokeFusionConfig(commandPermissionLevel = 3))

        assertEquals(3, store.load().commandPermissionLevel)
        assertTrue(Files.readString(directory.resolve("pokefusion.json")).contains("\"commandPermissionLevel\": 3"))
    }
}
