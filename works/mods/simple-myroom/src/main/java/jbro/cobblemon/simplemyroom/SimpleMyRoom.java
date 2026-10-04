package jbro.cobblemon.simplemyroom;

import jbro.cobblemon.simplemyroom.config.ConfigManager;
import jbro.cobblemon.simplemyroom.config.SimpleMyRoomConfig;
import jbro.cobblemon.simplemyroom.config.SimpleMyRoomMessages;
import jbro.cobblemon.simplemyroom.room.RoomProtectionService;
import jbro.cobblemon.simplemyroom.room.RoomService;
import jbro.cobblemon.simplemyroom.room.ActiveRoomService;
import jbro.cobblemon.simplemyroom.room.VisitorNotificationService;
import jbro.cobblemon.simplemyroom.room.RoomPreparationService;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimpleMyRoom implements ModInitializer {
    public static final String MOD_ID = "simple_myroom";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ConfigManager CONFIG_MANAGER = new ConfigManager();

    @Override
    public void onInitialize() {
        ConfigManager.ReloadResult loaded = CONFIG_MANAGER.load();
        if (!loaded.successful()) {
            LOGGER.error("Could not load Simple MyRoom configuration. Built-in defaults will be used: {}", loaded.error());
        }
        if (!config().general.enabled) {
            LOGGER.warn("Simple MyRoom is disabled by configuration.");
            return;
        }
        ActiveRoomService activeRoomService = new ActiveRoomService();
        RoomPreparationService roomPreparationService = new RoomPreparationService();
        roomPreparationService.register();
        RoomService roomService = new RoomService(activeRoomService, roomPreparationService);
        VisitorNotificationService visitorNotificationService = new VisitorNotificationService(roomService::ejectUnauthorizedPhysicalEntrant);
        visitorNotificationService.register();
        roomService.register();
        new RoomProtectionService().register();
        LOGGER.info("Simple MyRoom initialized with configuration at {}.", CONFIG_MANAGER.configPath());
    }

    public static SimpleMyRoomConfig config() {
        return CONFIG_MANAGER.config();
    }

    public static SimpleMyRoomMessages messages() {
        return CONFIG_MANAGER.messages();
    }

    public static boolean enabled() {
        return config().general.enabled;
    }

    public static boolean protectionEnabled() {
        return enabled() && config().protection.enabled;
    }

    public static ConfigManager.ReloadResult reloadConfig() {
        return CONFIG_MANAGER.load();
    }
}
