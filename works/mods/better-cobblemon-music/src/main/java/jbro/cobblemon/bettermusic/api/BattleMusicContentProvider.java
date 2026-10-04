package jbro.cobblemon.bettermusic.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tells Better Cobblemon Music which content runs a battle, so the battle can have its own music. Content keys are
 * lowercase namespaced IDs mapped under {@code battle.content}.
 */
@FunctionalInterface
public interface BattleMusicContentProvider {
    /** The content running the battle, or empty when this provider does not know it. */
    Optional<String> contentId(UUID battleId);

    /**
     * The battle's content keys from the most specific to the most general, such as
     * {@code example:league/champion} before {@code example:league}. The first key with a mapping plays.
     */
    default List<String> contentKeys(UUID battleId) {
        Optional<String> contentId = contentId(battleId);
        return contentId == null ? null : contentId.map(List::of).orElse(List.of());
    }

    /** Keys this provider can return, offered in the settings screen even before a mapping exists. */
    default Set<String> knownContentKeys() {
        return Set.of();
    }
}
