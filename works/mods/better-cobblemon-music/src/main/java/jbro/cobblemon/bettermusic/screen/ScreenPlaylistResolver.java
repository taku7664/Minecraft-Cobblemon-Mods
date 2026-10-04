package jbro.cobblemon.bettermusic.screen;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

/** Picks the playlist of an open screen: the first of its keys, most specific first, that has a mapping. */
public final class ScreenPlaylistResolver {
    private final Map<String, PlaylistDefinition> screens;

    public ScreenPlaylistResolver(Map<String, PlaylistDefinition> screens) {
        this.screens = Map.copyOf(Objects.requireNonNull(screens, "screens"));
    }

    public Optional<Selection> select(List<String> screenKeys) {
        Objects.requireNonNull(screenKeys, "screenKeys");
        for (String key : screenKeys) {
            PlaylistDefinition playlist = screens.get(key);
            if (playlist != null) {
                return Optional.of(new Selection("screen:" + key, playlist));
            }
        }
        return Optional.empty();
    }

    public record Selection(String id, PlaylistDefinition playlist) {
        public Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
        }
    }
}
