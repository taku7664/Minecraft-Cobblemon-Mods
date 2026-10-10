package jbro.cobblemon.clientsetup;

import java.io.IOException;
import java.util.stream.Collectors;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keep this class independent of Minecraft and other mods' classes during early startup. */
public final class ClientSetupPreLaunch implements PreLaunchEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("cobblemon-client-setup");

    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT) return;
        try {
            ClientSetup.migrateLegacyConfig(loader.getConfigDir());
        } catch (IOException exception) {
            LOGGER.error("Could not migrate client setup config; skipping presets to preserve existing user choices", exception);
            return;
        }
        var installedMods = loader.getAllMods().stream().map(mod -> mod.getMetadata().getId()).collect(Collectors.toSet());
        try {
            if (ClientSetup.apply(loader.getConfigDir(), loader.isModLoaded("cobbled_level_control"))
                == ClientSetup.Result.APPLIED) {
                LOGGER.info("Applied client setup: CLC client.hud.enabled=false");
            }
        } catch (IOException exception) {
            LOGGER.error("Could not apply CLC HUD default; initialization will retry next launch", exception);
        }
        try {
            int applied = KeybindingSetup.apply(loader.getGameDir(), loader.getConfigDir(),
                installedMods);
            if (applied > 0) LOGGER.info("Applied keybinding cleanup for {} installed mods", applied);
        } catch (IOException exception) {
            LOGGER.error("Could not apply keybinding defaults; initialization will retry next launch", exception);
        }
        try {
            int moved = XaeroWorldReset.apply(loader.getGameDir(), loader.getConfigDir());
            if (moved > 0) LOGGER.info("Moved {} Xaero map caches of the reset live server aside", moved);
        } catch (IOException exception) {
            LOGGER.error("Could not move the reset live server's Xaero map caches; initialization will retry next launch", exception);
        }
        try {
            int applied = XaeroSetup.apply(loader.getGameDir(), loader.getConfigDir(), installedMods);
            if (applied > 0) LOGGER.info("Applied Xaero map preset for {} installed map mods", applied);
        } catch (IOException exception) {
            LOGGER.error("Could not apply Xaero map defaults; initialization will retry next launch", exception);
        }
        try {
            if (RoundingBlockSetup.apply(loader.getConfigDir(), installedMods) == ClientSetup.Result.APPLIED) {
                LOGGER.info("Applied client setup: Rounding-Block enabled=false");
            }
        } catch (IOException exception) {
            LOGGER.error("Could not apply Rounding-Block default; initialization will retry next launch", exception);
        }
    }
}
