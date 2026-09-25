package jbro.cobblemon.battleui.dialogue;

import java.util.ArrayDeque;
import java.util.List;

/** Keeps battle narration in arrival order until the player acknowledges it. */
public final class BattleDialogueQueue<T> {
    private final ArrayDeque<T> messages = new ArrayDeque<>();
    private boolean confirmHeld;

    public synchronized void enqueue(List<T> incoming) {
        messages.addAll(incoming);
    }

    public synchronized T current() {
        return messages.peekFirst();
    }

    public synchronized boolean hasPending() {
        return !messages.isEmpty();
    }

    public synchronized boolean isConfirmHeld() {
        return confirmHeld;
    }

    public synchronized boolean pressConfirm() {
        if (confirmHeld || messages.isEmpty()) return false;
        confirmHeld = true;
        messages.removeFirst();
        return true;
    }

    public synchronized void releaseConfirm() {
        confirmHeld = false;
    }

    public synchronized void clear() {
        messages.clear();
        confirmHeld = false;
    }
}
