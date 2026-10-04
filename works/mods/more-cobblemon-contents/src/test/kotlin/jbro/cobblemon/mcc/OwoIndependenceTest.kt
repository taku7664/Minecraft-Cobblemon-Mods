package jbro.cobblemon.mcc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The MCC mods and the UI kit lay out and draw their screens themselves. owo-lib reaches development runs only as
 * Accessories' requirement (through Mega Showdown); none of these mods may depend on it or use it.
 */
class OwoIndependenceTest {
    private val modules = listOf(
        "cobblemon-ui",
        "more-cobblemon-contents",
        "more-cobblemon-contents-battle-tower",
        "more-cobblemon-contents-pvp",
        "more-cobblemon-contents-battle-factory",
        "more-cobblemon-contents-league-challenge",
    )

    private val repository: Path = Path.of("").toAbsolutePath().parent

    @Test
    fun `no mod metadata declares owo`() {
        modules.forEach { module ->
            val metadata = repository.resolve("$module/src/main/resources/fabric.mod.json").readText()
            assertTrue(!metadata.contains("owo"), "$module fabric.mod.json mentions owo")
        }
    }

    @Test
    fun `no mod source uses owo`() {
        modules.forEach { module ->
            Files.walk(repository.resolve("$module/src/main")).use { paths ->
                paths.filter { it.isRegularFile() && it.extension in setOf("kt", "java", "xml") }.forEach { file ->
                    assertTrue(!file.readText().contains("io.wispforest"), "$file uses owo")
                }
            }
        }
    }

    @Test
    fun `no mod build file names owo`() {
        modules.forEach { module ->
            assertTrue(!repository.resolve("$module/build.gradle.kts").readText().contains("owo"), "$module build names owo")
        }
    }
}
