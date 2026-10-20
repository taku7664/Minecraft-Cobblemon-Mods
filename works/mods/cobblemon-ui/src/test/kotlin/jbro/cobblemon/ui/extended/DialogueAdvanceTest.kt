package jbro.cobblemon.ui.extended

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueAdvanceTest {
    @Test fun `a line is written out and finishing shows all of it`() {
        val advance = DialogueAdvance(length = { 400 }, advance = {})
        assertFalse(advance.complete())
        advance.finish()
        assertTrue(advance.complete())
        advance.restart()
        assertFalse(advance.complete())
    }

    @Test fun `nothing on screen counts as complete and is never pressed`() {
        val advance = DialogueAdvance(length = { null }, advance = { error("nothing to move on from") })
        assertTrue(advance.complete())
        assertFalse(advance.press(DialogueAdvance.KEYBOARD))
    }

    @Test fun `one typing speed`() {
        assertEquals(80.0, DialogueAdvance.CHARACTERS_PER_SECOND)
        assertEquals(1000L, DialogueAdvance.millisFor(80))
    }

    /** Every message box takes its typing and its confirm/click/hold from the one place, none keeps its own. */
    @Test fun `every message box uses the shared advance`() {
        val boxes = listOf(
            "src/main/kotlin/jbro/cobblemon/ui/extended/BattleDialogue.kt",
            "src/main/kotlin/jbro/cobblemon/ui/extended/SceneDialogue.kt",
            "../cobblemon-npc/src/main/kotlin/jbro/cobblemon/npc/client/NpcDialogueScreen.kt",
            "../jbro-policy/src/main/kotlin/jbro/cobblemon/policy/client/PichuScreen.kt",
        )
        boxes.forEach { file ->
            val source = Files.readString(Path.of(file))
            assertTrue(source.contains("DialogueAdvance"), file)
            assertFalse(source.contains("CHARACTERS_PER_SECOND"), file)
            assertFalse(source.contains("HoldRepeat("), file)
        }
    }
}
