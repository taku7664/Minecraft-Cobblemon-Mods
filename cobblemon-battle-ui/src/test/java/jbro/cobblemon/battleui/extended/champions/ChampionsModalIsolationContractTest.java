package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChampionsModalIsolationContractTest {
    @Test
    void modalUsesDepthOneThousandAndSuspendsUnderlyingMoveHover() throws Exception {
        String uiUtils = read("src/main/kotlin/com/cobblemonextendedbattleui/UIUtils.kt");
        String overlay = read("src/main/kotlin/com/cobblemonextendedbattleui/ui/champions/ChampionsBattleInfoOverlay.kt");
        String tooltip = read("src/main/kotlin/com/cobblemonextendedbattleui/MoveTooltipRenderer.kt");
        String moveMixin = read("src/main/java/com/cobblemonextendedbattleui/mixin/BattleMoveSelectionMixin.java");
        String guiMixin = read("src/main/java/com/cobblemonextendedbattleui/mixin/BattleGuiNavigationMixin.java");

        assertTrue(uiUtils.contains("const val MODAL_Z_OFFSET = 1000.0"));
        assertTrue(overlay.contains("UIUtils.MODAL_Z_OFFSET"));
        assertTrue(tooltip.contains("fun suspendForModal()"));
        assertTrue(tooltip.contains("if (BattleInfoPanel.isExpanded) return"));
        assertTrue(moveMixin.contains("MoveTooltipRenderer.INSTANCE.suspendForModal();"));

        int mouseObserver = guiMixin.indexOf("cobblemonBattleUi$observeMouseMovement");
        int foreground = guiMixin.indexOf("cobblemonBattleUi$renderInformationOverlayLast");
        assertTrue(mouseObserver >= 0 && foreground > mouseObserver);
        assertTrue(guiMixin.substring(mouseObserver, foreground)
            .contains("if (BattleInfoPanel.INSTANCE.isExpanded())"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
