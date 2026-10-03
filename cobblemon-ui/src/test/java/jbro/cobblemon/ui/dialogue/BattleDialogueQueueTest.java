package jbro.cobblemon.ui.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BattleDialogueQueueTest {
    @Test
    void messagesAdvanceInArrivalOrderOnePerKeyPress() {
        BattleDialogueQueue<String> queue = new BattleDialogueQueue<>();
        queue.enqueue(List.of("First", "Second", "Third"));

        assertEquals("First", queue.current());
        assertTrue(queue.pressConfirm());
        assertEquals("Second", queue.current());
        assertFalse(queue.pressConfirm());
        assertEquals("Second", queue.current());

        queue.releaseConfirm();
        assertTrue(queue.pressConfirm());
        assertEquals("Third", queue.current());
        queue.releaseConfirm();
        assertTrue(queue.pressConfirm());
        assertNull(queue.current());
    }

    @Test
    void emptyQueueDoesNotConsumeConfirmAndClearResetsKeyLatch() {
        BattleDialogueQueue<String> queue = new BattleDialogueQueue<>();
        assertFalse(queue.pressConfirm());

        queue.enqueue(List.of("Old"));
        queue.clear();
        queue.enqueue(List.of("New"));
        assertTrue(queue.pressConfirm());
        assertNull(queue.current());
    }

    @Test
    void spectatorBatchesReplaceUnreadLinesAndAdvanceWithoutTheKey() {
        BattleDialogueQueue<String> queue = new BattleDialogueQueue<>();
        queue.enqueue(List.of("Old", "Older"));
        assertTrue(queue.pressConfirm());

        queue.replace(List.of("New", "Newer"));
        assertEquals("New", queue.current());
        assertTrue(queue.isConfirmHeld());

        queue.releaseConfirm();
        assertTrue(queue.advance());
        assertTrue(queue.advance());
        assertNull(queue.current());
        assertFalse(queue.advance());
    }
}
