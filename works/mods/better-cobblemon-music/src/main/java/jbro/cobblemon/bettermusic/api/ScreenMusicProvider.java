package jbro.cobblemon.bettermusic.api;

import java.util.List;
import java.util.Set;

/**
 * Tells Better Cobblemon Music that a screen with its own music is open, such as a content hub. Screen keys are
 * lowercase namespaced IDs mapped under {@code screens}; screen music replaces field music while the screen is open,
 * and battle music still wins over it.
 */
@FunctionalInterface
public interface ScreenMusicProvider {
    /**
     * The open screen's keys from the most specific to the most general, such as {@code example:hub/shop} before
     * {@code example:hub}, or an empty list when none of this provider's screens is open. Called on the client thread.
     */
    List<String> screenKeys();

    /** Keys this provider can return, offered in the settings screen even before a mapping exists. */
    default Set<String> knownScreenKeys() {
        return Set.of();
    }
}
