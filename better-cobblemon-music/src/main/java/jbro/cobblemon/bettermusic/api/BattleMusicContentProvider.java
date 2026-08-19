package jbro.cobblemon.bettermusic.api;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface BattleMusicContentProvider {
    Optional<String> contentId(UUID battleId);
}
