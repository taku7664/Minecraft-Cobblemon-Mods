package jbro.cobblemon.ui.transcript;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptIntegrationContractTest {
    private String read(String path) throws Exception { return Files.readString(Path.of("src/main/" + path)); }
    private String mixin(String file) throws Exception { return read("java/jbro/cobblemon/ui/extended/mixin/" + file + ".java"); }

    @Test void historyInputWinsBeforeDialogueAndCombatNavigation() throws Exception {
        String source = mixin("ScreenBattleNavigationMixin");
        int log = source.indexOf("BattleTranscriptOverlay.INSTANCE.keyPressed");
        assertTrue(log > 0 && log < source.indexOf("BattleDialogue.INSTANCE.confirm"));
        assertTrue(mixin("KeyboardBattleNavigationMixin").contains("BattleTranscriptOverlay.INSTANCE.releaseKey"));
        assertTrue(mixin("BattleGuiNavigationMixin").contains("BattleTranscriptOverlay.INSTANCE.mouseClicked"));
        assertTrue(mixin("BattleGuiNavigationMixin").contains("@Inject(method = \"charTyped\""));
        assertTrue(mixin("MouseScrollMixin").indexOf("BattleTranscriptOverlay.INSTANCE.scrollBy") <
                mixin("MouseScrollMixin").indexOf("MoveTooltipRenderer.INSTANCE.handleScroll"));
    }
    @Test void transcriptUsesOnlyPokemonPortraitsAndFlushesBeforeUnclipping() throws Exception {
        String portrait = read("kotlin/jbro/cobblemon/ui/extended/ui/transcript/TranscriptPortraits.kt");
        assertTrue(portrait.contains("drawPosablePortrait("));
        assertTrue(portrait.contains("BattleOverlay.PORTRAIT_DIAMETER"));
        assertFalse(portrait.contains("PlayerSkinDrawer"));
        String overlay = read("kotlin/jbro/cobblemon/ui/extended/ui/transcript/BattleTranscriptOverlay.kt");
        assertTrue(overlay.contains("context.draw() // Text batches must flush"));
        assertTrue(overlay.contains("color, false)"));
    }
    @Test void shutdownFrameCannotReadPastCapturePages() throws Exception {
        String fixture = read("kotlin/jbro/cobblemon/ui/extended/ui/shared/BattleThemeCapture.kt");
        assertEquals(2, fixture.split("if \\(page !in pages.indices\\) return", -1).length - 1);
    }
}
