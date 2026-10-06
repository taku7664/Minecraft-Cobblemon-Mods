package jbro.cobblemon.battlecam.api;

import com.batmite2b.battlecam.client.BattleCamClient;

/**
 * Lets other mods point the battle camera at an entity for a scripted moment, such as a trainer's words when a
 * battle is won or lost. The focus outlasts the battle it follows, takes over from any battle shot, and is skipped
 * when the player has the camera off for that battle. Call it on the client thread.
 */
public final class BattlecamScenes {
    private BattlecamScenes() {
    }

    /**
     * Looks at the client entity with network id {@code entityId} for at most {@code durationMillis}, or until
     * {@link #release()} or the entity leaves. Returns false when the camera stays where it is.
     */
    public static boolean focus(int entityId, long durationMillis) {
        return BattleCamClient.STATE.startScene(entityId, durationMillis);
    }

    /** Ends the current focus; the battle shots (or the player's own view) take back over. */
    public static void release() {
        BattleCamClient.STATE.endScene();
    }

    public static boolean isFocusing() {
        return BattleCamClient.STATE.isSceneActive();
    }
}
