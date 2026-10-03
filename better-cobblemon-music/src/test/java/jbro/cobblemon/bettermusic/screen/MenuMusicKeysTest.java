package jbro.cobblemon.bettermusic.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MenuMusicKeysTest {
    @Test
    void onlyTheTitleScreenOutsideAWorldOwnsMenuMusic() {
        assertEquals(List.of("minecraft:title"), MenuMusicKeys.resolve(false, true));
        assertEquals(List.of(), MenuMusicKeys.resolve(true, true));
        assertEquals(List.of(), MenuMusicKeys.resolve(true, false));
        assertEquals(List.of(), MenuMusicKeys.resolve(false, false));
    }
}
