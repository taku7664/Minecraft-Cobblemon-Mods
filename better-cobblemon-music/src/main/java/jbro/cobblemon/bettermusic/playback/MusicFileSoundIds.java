package jbro.cobblemon.bettermusic.playback;

import java.util.Locale;
import java.util.Objects;

public final class MusicFileSoundIds {
    private static final String PREFIX = "better_cobblemon_music:custom/";

    private MusicFileSoundIds() {
    }

    public static String soundEvent(String relativeOggPath) {
        Objects.requireNonNull(relativeOggPath, "relativeOggPath");
        String normalized = relativeOggPath.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".ogg")) {
            throw new IllegalArgumentException("relativeOggPath must end with .ogg");
        }
        return PREFIX + normalized.substring(0, normalized.length() - ".ogg".length());
    }
}
