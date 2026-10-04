package jbro.cobblemon.ui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsActiveLayoutContractTest {
    @Test
    void modalShowsVerticalTeamRailsAndActivePokemonPortraits() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/kotlin/jbro/cobblemon/ui/extended/ui/champions/ChampionsBattleInfoOverlay.kt"
        ));

        // Laid out at the GUI's own scale: no whole-window shrink, no fractional text scale.
        assertFalse(source.contains("BASE_W"));
        assertFalse(source.contains("matrices.scale("));
        assertTrue(source.contains("ChampionsInfoLayout.calculate("));
        assertTrue(source.contains("private var activeAllies: List<PokemonEntry>"));
        assertTrue(source.contains("private var activeOpponents: List<PokemonEntry>"));
        assertTrue(source.contains("drawSide(context, layout.ally, activeAllies, allyTeam, opponent = false)"));
        assertTrue(source.contains("drawField(context, layout.field)"));
        assertTrue(source.contains("drawSide(context, layout.opponent, activeOpponents, opponentTeam, opponent = true)"));
        assertTrue(source.contains("private const val MAX_ACTIVE_PER_SIDE = 3"));
        assertTrue(source.contains("val shown = entries.take(MAX_ACTIVE_PER_SIDE)"));
        assertTrue(source.contains("TranscriptPortraits.draw("));
        assertFalse(source.contains("drawPublicInfo("));
        assertFalse(source.matches("(?s).*, 0\\.[0-7][0-9]*f\\).*"));
    }
}
