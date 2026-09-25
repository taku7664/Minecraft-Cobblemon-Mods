package jbro.cobblemon.battleui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class Cobblemon181MixinSignatureContractTest {
    @Test
    void wildBattleForfeitHookIncludesAllCobblemon181LambdaParameters() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleGeneralActionSelectionMixin.java"
        ));

        assertTrue(source.replace("\r\n", "\n").contains(
            "BattleGUI battleGUI,\n            SingleActionRequest request,\n            BattleGeneralActionSelection selection,"
        ));
    }
}
