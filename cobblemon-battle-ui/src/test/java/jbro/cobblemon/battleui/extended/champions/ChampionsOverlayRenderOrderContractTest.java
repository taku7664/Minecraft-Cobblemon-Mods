package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsOverlayRenderOrderContractTest {
    @Test
    void battleHudOnlyUpdatesStateAndBattleScreenCompositesModalLast() throws Exception {
        String panel = Files.readString(Path.of(
            "src/main/kotlin/com/cobblemonextendedbattleui/BattleInfoPanel.kt"
        ));
        String mixin = Files.readString(Path.of(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleGuiNavigationMixin.java"
        ));

        int updateStart = panel.indexOf("fun update() {");
        int foregroundStart = panel.indexOf("fun renderForeground(context: DrawContext) {");
        assertTrue(updateStart >= 0);
        assertTrue(foregroundStart > updateStart);
        assertFalse(panel.substring(updateStart, foregroundStart)
            .contains("ChampionsBattleInfoOverlay.render(context)"));
        assertTrue(panel.substring(foregroundStart)
            .contains("ChampionsBattleInfoOverlay.render(context)"));

        assertTrue(mixin.contains("@Inject(method = \"render\", at = @At(\"RETURN\"), remap = true)"));
        assertTrue(mixin.contains("BattleInfoPanel.INSTANCE.renderForeground(context);"));
    }
}
