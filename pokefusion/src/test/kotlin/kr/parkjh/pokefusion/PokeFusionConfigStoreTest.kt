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
    fun `out of range permission falls back without overwriting user file`() {
        val path = directory.resolve("pokefusion.json")
        val invalid = """{"commandPermissionLevel":9}"""
        Files.writeString(path, invalid)

        assertEquals(0, PokeFusionConfigStore(directory).load().commandPermissionLevel)
        assertEquals(invalid, Files.readString(path))
    }

    @Test
    fun `malformed config falls back without overwriting user file`() {
        val path = directory.resolve("pokefusion.json")
        val malformed = "{broken"
        Files.writeString(path, malformed)

        assertEquals(0, PokeFusionConfigStore(directory).load().commandPermissionLevel)
        assertEquals(malformed, Files.readString(path))
    }

    @Test
    fun `unwritable config location keeps public command access`() {
        val occupiedPath = directory.resolve("not-a-directory")
        Files.writeString(occupiedPath, "occupied")

        assertEquals(0, PokeFusionConfigStore(occupiedPath).load().commandPermissionLevel)
    }
}
