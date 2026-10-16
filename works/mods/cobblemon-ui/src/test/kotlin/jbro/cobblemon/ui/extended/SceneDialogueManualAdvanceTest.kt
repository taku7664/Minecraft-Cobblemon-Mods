package jbro.cobblemon.ui.extended

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneDialogueManualAdvanceTest {
    @Test fun `scene dialogue advances only through confirmation`() {
        val source = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/ui/extended/SceneDialogue.kt"))
        assertFalse(source.contains("DWELL_MILLIS"))
        assertFalse(source.contains("now - completeMillis"))
        assertTrue(source.contains("private fun confirm()"))
        assertTrue(source.contains("else {\n            next()"))
    }
}
