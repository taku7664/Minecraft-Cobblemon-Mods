package jbro.cobblemon.ui.dialogue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.KeyboardHandler;
import org.junit.jupiter.api.Test;

final class BattleDialogueKeyReleaseContractTest {
    @Test
    void releaseHookTargetsAMethodDeclaredByKeyboard() throws Exception {
        KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class);

        String source = Files.readString(Path.of(
            "src/main/java/jbro/cobblemon/ui/extended/mixin/KeyboardBattleNavigationMixin.java"
        ));
        assertTrue(source.contains("@Mixin(KeyboardHandler.class)"));
        assertTrue(source.contains("@Inject(method = \"keyPress\""));
        assertTrue(Files.readString(Path.of("src/main/resources/cobblemon_ui.mixins.json"))
            .contains("\"KeyboardBattleNavigationMixin\""));
        assertFalse(Files.readString(Path.of(
            "src/main/java/jbro/cobblemon/ui/extended/mixin/ScreenBattleNavigationMixin.java"
        )).contains("@Inject(method = \"keyReleased\""));
    }
}
