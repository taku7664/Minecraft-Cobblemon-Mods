package jbro.cobblemon.ui.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HoldRepeatTest {
    @Test
    void repeatsAfterThePauseThenAtTheInterval() {
        HoldRepeat hold = new HoldRepeat(400, 70);
        assertFalse(hold.due(1_000));

        hold.press(0);
        assertTrue(hold.isHeld());
        assertFalse(hold.due(399));
        assertTrue(hold.due(400));
        assertFalse(hold.due(469));
        assertTrue(hold.due(470));
        // A long frame yields one step, not a burst.
        assertTrue(hold.due(2_000));
        assertFalse(hold.due(2_000));

        hold.release();
        assertFalse(hold.isHeld());
        assertFalse(hold.due(10_000));
    }

    @Test
    void heldConfirmRunsThroughTheQueueOnlyWhileHeld() {
        BattleDialogueQueue<String> queue = new BattleDialogueQueue<>();
        queue.enqueue(List.of("First", "Second", "Third"));
        assertFalse(queue.repeatConfirm());

        assertTrue(queue.pressConfirm());
        assertTrue(queue.repeatConfirm());
        assertEquals("Third", queue.current());
        assertTrue(queue.isConfirmHeld());
        assertTrue(queue.repeatConfirm());
        assertNull(queue.current());
        assertFalse(queue.repeatConfirm());
    }
}
