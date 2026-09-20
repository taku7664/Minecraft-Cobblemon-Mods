package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsRankTrackContractTest {
    @Test
    void everyBattleStatUsesAVisibleSixCellRankTrackInBothCardLayouts() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/kotlin/com/cobblemonextendedbattleui/ui/champions/ChampionsBattleInfoOverlay.kt"
        ));

        assertTrue(source.contains("private val STAT_ORDER = listOf("));
        assertTrue(source.contains("private fun drawRankTrack("));
        assertTrue(source.contains("repeat(6) { index ->"));
        assertTrue(source.contains("drawDetailedRankRows(context, entry.uuid"));
        assertTrue(source.contains("drawCompactRankGrid(context, entry.uuid"));
    }
}
