package jbro.cobblemon.ui.extended

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneDialogueManualAdvanceTest {
    @Test fun `scene dialogue advances only through confirmation`() {
        // A checkout with core.autocrlf has CRLF line ends.
        val source = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/ui/extended/SceneDialogue.kt"))
            .replace("\r\n", "\n")
        assertFalse(source.contains("DWELL_MILLIS"))
        assertFalse(source.contains("completeMillis"))
        // Moving on is the shared confirm's step, never a timer.
        assertTrue(source.contains("advance = { next() }"))
    }
}
