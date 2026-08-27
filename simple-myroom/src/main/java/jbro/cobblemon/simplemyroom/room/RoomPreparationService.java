package jbro.cobblemon.simplemyroom.room;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class RoomPreparationService {
    private final Map<Long, PendingPreparation> pending = new LinkedHashMap<>();

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> pending.clear());
    }

    public QueueResult enqueue(
        long roomIndex,
        UUID requester,
        RoomWorldInitializer.PreparationTask task,
        Runnable onComplete,
        Runnable onFailure
    ) {
        PendingPreparation existing = pending.get(roomIndex);
        if (existing != null) {
            existing.waiters.put(requester, new Waiter(onComplete, onFailure));
            return QueueResult.JOINED;
        }
        if (pending.size() >= SimpleMyRoom.config().roomPreparation.maxQueuedRooms) return QueueResult.FULL;
        PendingPreparation created = new PendingPreparation(task);
        created.waiters.put(requester, new Waiter(onComplete, onFailure));
        if (!SimpleMyRoom.config().roomPreparation.enabled) {
            try {
                while (!task.done()) task.process(Integer.MAX_VALUE);
                onComplete.run();
                return QueueResult.COMPLETED_SYNCHRONOUSLY;
            } catch (RuntimeException exception) {
                SimpleMyRoom.LOGGER.error("Room {} preparation failed.", roomIndex, exception);
                onFailure.run();
                return QueueResult.FAILED;
            }
        }
        pending.put(roomIndex, created);
        return QueueResult.QUEUED;
    }

    private void tick() {
        if (pending.isEmpty()) return;
        Map.Entry<Long, PendingPreparation> entry = pending.entrySet().iterator().next();
        PendingPreparation preparation = entry.getValue();
        try {
            preparation.task.process(SimpleMyRoom.config().roomPreparation.blocksPerTick);
        } catch (RuntimeException exception) {
            pending.remove(entry.getKey());
            SimpleMyRoom.LOGGER.error("Room {} preparation failed.", entry.getKey(), exception);
            preparation.waiters.values().forEach(waiter -> runSafely(entry.getKey(), waiter.onFailure, "failure callback"));
            return;
        }
        if (!preparation.task.done()) return;
        pending.remove(entry.getKey());
        preparation.waiters.values().forEach(waiter -> runSafely(entry.getKey(), waiter.onComplete, "completion callback"));
    }

    private void runSafely(long roomIndex, Runnable callback, String callbackName) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            SimpleMyRoom.LOGGER.error("Room {} {} failed.", roomIndex, callbackName, exception);
        }
    }

    private static final class PendingPreparation {
        private final RoomWorldInitializer.PreparationTask task;
        private final Map<UUID, Waiter> waiters = new LinkedHashMap<>();

        private PendingPreparation(RoomWorldInitializer.PreparationTask task) {
            this.task = task;
        }
    }

    private record Waiter(Runnable onComplete, Runnable onFailure) {
    }

    public enum QueueResult {
        QUEUED,
        JOINED,
        FULL,
        COMPLETED_SYNCHRONOUSLY,
        FAILED
    }
}
