package jbro.cobblemon.battlespeed;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.network.BattleSpeedPayload;
import jbro.cobblemon.battlespeed.network.BattleSpeedSaveConfigPayload;
import jbro.cobblemon.battlespeed.network.BattleSpeedSetLevelPayload;
import jbro.cobblemon.battlespeed.network.BattleSpeedSyncPayload;
import jbro.cobblemon.battlespeed.server.BattleSpeedCommands;
import jbro.cobblemon.battlespeed.server.BattleSpeedServerNetworking;
import jbro.cobblemon.battlespeed.server.BattleSpeedService;
import jbro.cobblemon.battlespeed.server.PlayerSpeedStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class BattleSpeed implements ModInitializer {
    public static final String MOD_ID = "cobblemon_battle_speed";
    public static final Logger LOGGER = LoggerFactory.getLogger("Cobblemon Battle Speed");

    private static final String PLAYER_FILE = "cobblemon_battle_speed_players.json";

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(BattleSpeedPayload.TYPE, BattleSpeedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BattleSpeedSyncPayload.TYPE, BattleSpeedSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BattleSpeedSetLevelPayload.TYPE, BattleSpeedSetLevelPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BattleSpeedSaveConfigPayload.TYPE, BattleSpeedSaveConfigPayload.CODEC);
        BattleSpeedServerNetworking.register();

        try {
            BattleSpeedService.setConfig(readConfig());
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("Could not load {}, battles run at the built-in defaults", configPath(), exception);
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(PLAYER_FILE);
            var store = new PlayerSpeedStore(file);
            try {
                store.load();
            } catch (IOException exception) {
                LOGGER.error("Could not read {}, everyone starts at the default level", file, exception);
            }
            BattleSpeedService.attach(server, store);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(BattleSpeedService::detach);

        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> BattleSpeedCommands.register(dispatcher)
        );

        CobblemonEvents.BATTLE_STARTED_POST.subscribe(
            Priority.NORMAL,
            (Consumer<BattleStartedEvent.Post>) event -> BattleSpeedService.broadcast(event.getBattle())
        );
    }

    /** Reads the config file, creating it with defaults first; throws when the file is broken. */
    public static BattleSpeedConfig readConfig() throws IOException {
        return BattleSpeedConfig.loadOrCreate(configPath());
    }

    public static void writeConfig(BattleSpeedConfig config) throws IOException {
        config.save(configPath());
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
    }
}
