package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsActiveLayoutContractTest {
    @Test
    void modalUsesLargeThreeColumnActiveBattleLayoutWithoutPublicIntelOrModels() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/kotlin/com/cobblemonextendedbattleui/ui/champions/ChampionsBattleInfoOverlay.kt"
        ));

        assertTrue(source.contains("private const val BASE_W = 840"));
        assertTrue(source.contains("private var activeAllies: List<PokemonEntry>"));
        assertTrue(source.contains("private var activeOpponents: List<PokemonEntry>"));
        assertTrue(source.contains("drawSidePanel(context, activeAllies, SIDE_X_LEFT, false)"));
        assertTrue(source.contains("drawEffectsPanel(context, FIELD_X)"));
        assertTrue(source.contains("drawSidePanel(context, activeOpponents, SIDE_X_RIGHT, true)"));
        assertTrue(source.contains("private const val MAX_ACTIVE_PER_SIDE = 3"));
        assertTrue(source.contains("val shown = entries.take(MAX_ACTIVE_PER_SIDE)"));
        assertTrue(source.contains("else if (height >= COMPACT_CONDITIONS_MIN_HEIGHT)"));

        assertFalse(source.contains("drawPublicInfo("));
        assertFalse(source.contains("PokemonModelRenderer"));
        assertFalse(source.matches("(?s).*, 0\\.[0-7][0-9]*f\\).*"));
    }
}
