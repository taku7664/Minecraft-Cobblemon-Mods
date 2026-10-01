package jbro.cobblemon.ui.dialogue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Keyboard;
import org.junit.jupiter.api.Test;

final class BattleDialogueKeyReleaseContractTest {
    @Test
    void releaseHookTargetsAMethodDeclaredByKeyboard() throws Exception {
        Keyboard.class.getDeclaredMethod("onKey", long.class, int.class, int.class, int.class, int.class);

        String source = Files.readString(Path.of(
            "src/main/java/jbro/cobblemon/ui/extended/mixin/KeyboardBattleNavigationMixin.java"
        ));
        assertTrue(source.contains("@Mixin(Keyboard.class)"));
        assertTrue(source.contains("@Inject(method = \"onKey\""));
        assertTrue(Files.readString(Path.of("src/main/resources/cobblemon_ui.mixins.json"))
            .contains("\"KeyboardBattleNavigationMixin\""));
        assertFalse(Files.readString(Path.of(
            "src/main/java/jbro/cobblemon/ui/extended/mixin/ScreenBattleNavigationMixin.java"
        )).contains("@Inject(method = \"keyReleased\""));
    }
}
