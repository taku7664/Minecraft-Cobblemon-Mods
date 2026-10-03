package jbro.cobblemon.ui.dialogue;

import java.util.ArrayDeque;
import java.util.List;

/** Keeps battle narration in arrival order until the player acknowledges it. */
public final class BattleDialogueQueue<T> {
    private final ArrayDeque<T> messages = new ArrayDeque<>();
    private boolean confirmHeld;

    public synchronized void enqueue(List<T> incoming) {
        messages.addAll(incoming);
    }

    /** Drops the lines not yet read and queues {@code incoming} in their place; a held key stays held. */
    public synchronized void replace(List<T> incoming) {
        messages.clear();
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

    /** A key-repeat step while confirm stays held: moves on without waiting for a release. */
    public synchronized boolean repeatConfirm() {
        if (!confirmHeld || messages.isEmpty()) return false;
        messages.removeFirst();
        return true;
    }

    /** Moves on without a key press, as spectators' narration does on its own. */
    public synchronized boolean advance() {
        if (messages.isEmpty()) return false;
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
