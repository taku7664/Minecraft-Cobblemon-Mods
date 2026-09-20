package jbro.cobblemon.battleui.navigation;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ActionSubmissionGate {
    private final AtomicBoolean locked = new AtomicBoolean();

    public boolean tryLock() {
        return locked.compareAndSet(false, true);
    }

    public void unlock() {
        locked.set(false);
    }

    public boolean isLocked() {
        return locked.get();
    }
}
