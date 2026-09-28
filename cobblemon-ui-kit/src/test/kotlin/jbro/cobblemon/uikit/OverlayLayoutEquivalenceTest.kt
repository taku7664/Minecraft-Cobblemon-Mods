package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The dialog and panel splits moved onto the layout tree; they must land where the hand-written arithmetic put them. */
class OverlayLayoutEquivalenceTest {
    @Test
    fun `dialogs keep their hand-written panel and buttons`() {
        for (width in 1..900 step 3) for (height in 1..500 step 3) for (hasCancel in listOf(false, true)) {
            val panel = UiRect((width - 260) / 2, (height - 120) / 2, 260, 120)
            val buttonWidth = if (hasCancel) 104 else 120
            val confirmX = if (hasCancel) panel.x + panel.width / 2 + 4 else panel.x + (panel.width - buttonWidth) / 2
            val layout = UiDialogLayout.calculate(width, height, hasCancel)
            val at = "$width x $height $hasCancel"
            assertEquals(panel, layout.panel, at)
            assertEquals(confirmX to panel.bottom - 34, layout.confirm.x to layout.confirm.y, at)
            assertEquals(buttonWidth, layout.confirm.width, at)
            if (hasCancel) assertEquals(panel.x + panel.width / 2 - buttonWidth - 4 to panel.bottom - 34, layout.cancel!!.x to layout.cancel!!.y, at)
        }
    }

    @Test
    fun `panel content bounds keep their hand-written inset`() {
        for (width in 0..200 step 3) for (height in 0..120 step 3) for (title in listOf(0, 12)) {
            val spec = UiPanelSpec(padding = UiInsets(6, 4, 5, 3))
            val bounds = UiRect(9, 4, width, height)
            val old = UiRect(bounds.x + 6, bounds.y + 4 + title, (width - 11).coerceAtLeast(0), (height - 7 - title).coerceAtLeast(0))
            assertEquals(old, spec.contentBounds(bounds, title), "$bounds $title")
        }
    }
}
