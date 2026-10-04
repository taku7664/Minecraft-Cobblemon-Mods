package jbro.cobblemon.policy.support

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InquiryReviewExecutableTest {
    @Test
    fun `a bare agy is found on the PATH, then where agy installs itself, and a full path is kept`() {
        val root = Files.createTempDirectory("agy-test")
        try {
            val onPath = Files.createDirectories(root.resolve("tools")).resolve("agy.exe").also { Files.createFile(it) }
            val installed = Files.createDirectories(root.resolve("local").resolve("agy").resolve("bin")).resolve("agy.exe")
                .also { Files.createFile(it) }
            val settings = InquiryReviewSettings()
            assertEquals(onPath.toString(), settings.executable(mapOf("Path" to root.resolve("tools").toString())))
            // A server started without the user's PATH still finds the installed CLI.
            assertEquals(installed.toString(), settings.executable(mapOf("PATH" to "", "LOCALAPPDATA" to root.resolve("local").toString())))
            assertEquals("agy", settings.executable(emptyMap()))
            assertEquals("D:\\cli\\agy.exe", settings.copy(command = "D:\\cli\\agy.exe").executable(emptyMap()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
