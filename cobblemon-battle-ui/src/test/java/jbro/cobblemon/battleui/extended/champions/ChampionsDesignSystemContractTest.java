package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsDesignSystemContractTest {
    @Test
    void championsOverlayUsesTheLocalSemanticThemeWithoutDependingOnMbcRuntime() throws Exception {
        String tokens = read("src/main/kotlin/com/cobblemonextendedbattleui/ui/shared/BattleUiDesignTokens.kt");
        String overlay = read("src/main/kotlin/com/cobblemonextendedbattleui/ui/champions/ChampionsBattleInfoOverlay.kt");
        String build = read("build.gradle.kts");
        String metadata = read("src/main/resources/fabric.mod.json");

        for (String role : new String[] {
            "SCRIM", "SHELL", "HEADER", "PANEL", "PANEL_ALT", "BORDER", "BORDER_BRIGHT", "TRACK",
            "ACCENT_PRIMARY", "ACCENT_SECONDARY", "ACCENT_CAUTION", "ACCENT_DANGER", "ACCENT_GOOD",
            "TEXT_PRIMARY", "TEXT_SECONDARY", "TEXT_DIM"
        }) {
            assertTrue(tokens.contains("val " + role + " ="), "missing semantic role " + role);
        }
        for (String value : new String[] {
            "0x6A030612u", "0xFF080E1Du", "0xFF0C1528u", "0xFF101A2Du", "0xFF0C1525u",
            "0xFF274562u", "0xFF3F7896u", "0xFF101724u", "0xFF39E4E4u", "0xFF9868FFu", "0xFFFFC84Au", "0xFFFF667Au",
            "0xFF62E39Bu", "0xFFEAF7FFu", "0xFFB9CAD8u", "0xFF71859Au"
        }) {
            assertTrue(tokens.contains(value), "missing canonical default " + value);
        }

        assertTrue(overlay.contains("BattleUiDesignTokens as Ui"));
        assertTrue(overlay.contains("Ui.SHELL"));
        assertTrue(overlay.contains("Ui.ACCENT_PRIMARY"));
        assertTrue(overlay.contains("Ui.ACCENT_DANGER"));
        assertTrue(overlay.contains("Ui.TEXT_PRIMARY"));

        assertFalse(overlay.contains("VIOLET_EDGE"));
        assertFalse(overlay.contains("MAGENTA_EDGE"));
        assertFalse(overlay.contains("ALLY_GLASS"));
        assertFalse(overlay.contains("ENEMY_GLASS"));

        assertFalse(build.contains("more-battle-content"));
        assertFalse(metadata.contains("cobblemon_more_battle_content"));
        assertFalse(overlay.contains("jbro.cobblemon.morebattlecontent"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
