package jbro.cobblemon.battlespeed.client;

import jbro.cobblemon.battlespeed.BattleSpeed;
import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;

/** What the server last told this client: its level and the server config, for the settings screen. */
public final class ClientBattleSpeedSettings {
    private static volatile int level = 1;
    private static volatile BattleSpeedConfig config;

    private ClientBattleSpeedSettings() {
    }

    public static void update(int newLevel, String configJson) {
        try {
            config = BattleSpeedConfig.fromJson(configJson);
            level = newLevel;
        } catch (IllegalArgumentException exception) {
            BattleSpeed.LOGGER.warn("The server sent a battle speed config this client could not read", exception);
        }
    }

    public static void clear() {
        config = null;
        level = 1;
    }

    /** Null until the server has synced, or when the server does not have the mod. */
    public static BattleSpeedConfig config() {
        return config;
    }

    public static int level() {
        return level;
    }
}
