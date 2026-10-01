package jbro.cobblemon.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class Cobblemon181MixinSignatureContractTest {
    @Test
    void wildBattleForfeitHookIncludesAllCobblemon181LambdaParameters() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/jbro/cobblemon/ui/extended/mixin/BattleGeneralActionSelectionMixin.java"
        )).replace("\r\n", "\n");

        assertTrue(source.replace("\r\n", "\n").contains(
            "BattleGUI battleGUI,\n            SingleActionRequest request,\n            BattleGeneralActionSelection selection,"
        ));
    }
}
