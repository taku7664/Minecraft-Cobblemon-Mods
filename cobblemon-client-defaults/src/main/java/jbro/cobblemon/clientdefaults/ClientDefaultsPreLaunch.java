package jbro.cobblemon.clientdefaults;

import java.io.IOException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keep this class independent of Minecraft and CLC classes during early startup. */
public final class ClientDefaultsPreLaunch implements PreLaunchEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("cobblemon-client-defaults");

    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT) return;
        try {
            if (ClientDefaults.apply(loader.getConfigDir(), loader.isModLoaded("cobbled_level_control"))
                == ClientDefaults.Result.APPLIED) {
                LOGGER.info("Applied client default: CLC client.hud.enabled=false");
            }
        } catch (IOException exception) {
            LOGGER.error("Could not apply CLC HUD default; initialization will retry next launch", exception);
        }
    }
}
