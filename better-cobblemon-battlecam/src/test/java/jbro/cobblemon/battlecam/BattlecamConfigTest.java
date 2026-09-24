package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BattlecamConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsEnableEveryBattleTypeInAutoMode() {
        BattlecamConfig config = BattlecamConfig.defaults();

        for (BattlecamBattleType type : BattlecamBattleType.values()) {
            assertTrue(config.enabled(type));
            assertEquals(BattlecamMode.AUTO, config.defaultMode(type));
        }
    }

    @Test
    void storeRoundTripsIndependentTypeSettings() throws Exception {
        Path path = temporaryDirectory.resolve("better_cobblemon_battlecam.json");
        BattlecamConfig expected = new BattlecamConfig(
            false, BattlecamMode.OFF,
            true, BattlecamMode.MANUAL,
            true, BattlecamMode.AUTO
        );

        BattlecamConfigStore.save(path, expected);
        BattlecamConfig actual = BattlecamConfigStore.load(path);

        assertEquals(expected, actual);
        assertTrue(Files.readString(path).contains("\"pveDefaultMode\": \"MANUAL\""));
    }

    @Test
    void missingModeFallsBackToAutoWithoutDisablingTheType() throws Exception {
        Path path = temporaryDirectory.resolve("better_cobblemon_battlecam.json");
        Files.writeString(path, "{\"wildEnabled\":true,\"wildDefaultMode\":null}");

        BattlecamConfig actual = BattlecamConfigStore.load(path);

        assertTrue(actual.enabled(BattlecamBattleType.WILD));
        assertEquals(BattlecamMode.AUTO, actual.defaultMode(BattlecamBattleType.WILD));
    }
}
