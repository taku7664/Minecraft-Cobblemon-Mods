package jbro.cobblemon.battlespeed.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleSpeedConfigTest {
    @Test
    void defaultsAreFourLevelsUpToFourTimes() {
        var config = new BattleSpeedConfig().normalized();
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0), config.speeds);
        assertEquals(1, config.defaultLevel);
        assertTrue(config.battles.wild);
        assertTrue(config.battles.npc);
        assertFalse(config.battles.pvp);
        assertEquals(0, config.skipMoveEffectsFromLevel);
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        var config = BattleSpeedConfig.fromJson("{\"speeds\": [1, 1.5], \"battles\": {\"wild\": false}}");
        assertEquals(List.of(1.0, 1.5), config.speeds);
        assertFalse(config.battles.wild);
        assertTrue(config.battles.npc);
        assertTrue(config.apply.messageWaits.enabled);
        assertEquals(1.0, config.apply.moveEffects.multiplier);
        assertEquals(2, config.permissions.reload);
    }

    @Test
    void outOfRangeValuesArePulledBack() {
        var config = BattleSpeedConfig.fromJson(
            "{\"speeds\": [0, 100], \"defaultLevel\": 9, \"multiplePlayers\": \"fastest\","
                + " \"skipMoveEffectsFromLevel\": -3, \"permissions\": {\"change\": -1, \"reload\": 7}}"
        );
        assertEquals(List.of(BattleSpeedConfig.MIN_SPEED, BattleSpeedConfig.MAX_SPEED), config.speeds);
        assertEquals(2, config.defaultLevel);
        assertEquals(BattleSpeedConfig.SLOWEST, config.multiplePlayers);
        assertEquals(0, config.skipMoveEffectsFromLevel);
        assertEquals(0, config.permissions.change);
        assertEquals(4, config.permissions.reload);
    }

    @Test
    void emptySpeedListStillLeavesOneLevel() {
        var config = BattleSpeedConfig.fromJson("{\"speeds\": []}");
        assertEquals(List.of(1.0), config.speeds);
        assertEquals(1, config.levelCount());
    }

    @Test
    void partsKeepNormalBattlesAtNormalSpeed() {
        var config = BattleSpeedConfig.fromJson("{\"apply\": {\"moveEffects\": {\"multiplier\": 2}}}");
        assertEquals(1.0, config.apply.moveEffects.factor(1.0));
        assertEquals(6.0, config.apply.moveEffects.factor(3.0));
        config.apply.moveEffects.enabled = false;
        assertEquals(1.0, config.apply.moveEffects.factor(3.0));
    }

    @Test
    void brokenJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BattleSpeedConfig.fromJson("{\"speeds\": [1, }"));
        assertThrows(IllegalArgumentException.class, () -> BattleSpeedConfig.fromJson(""));
    }

    @Test
    void missingFileIsCreatedWithDefaultsAndReadsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config").resolve("cobblemon_battle_speed.json");
        var created = BattleSpeedConfig.loadOrCreate(file);
        assertTrue(Files.exists(file));
        var reread = BattleSpeedConfig.loadOrCreate(file);
        assertEquals(created.speeds, reread.speeds);
        assertEquals(created.toJson(), reread.toJson());
    }
}
