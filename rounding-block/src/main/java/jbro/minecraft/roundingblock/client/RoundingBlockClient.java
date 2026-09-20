package jbro.minecraft.roundingblock.client;

import java.nio.file.Path;
import jbro.minecraft.roundingblock.client.command.RoundingBlockCommands;
import jbro.minecraft.roundingblock.client.render.fluid.RoundedWaterRenderHandler;
import jbro.minecraft.roundingblock.client.settings.RoundingBlockConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoundingBlockClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");

    @Override
    public void onInitializeClient() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("rounding-block.json");
        RoundingBlockConfig config = RoundingBlockConfig.load(
            configPath,
            LOGGER::warn
        );
        if (config.debug().diagnosticLogging()) {
            LOGGER.info(
                "Configuration loaded: enabled={}, radius={}, segments={}, mesh caches={}/{}/{}/{}, weighted variants={}",
                config.enabled(),
                config.quality().radius(),
                config.quality().segments(),
                config.cache().fullBlockPlans(),
                config.cache().slabPlans(),
                config.cache().complexShapePlans(),
                config.cache().fluidContactPlans(),
                config.cache().weightedModelVariants()
            );
        }
        RoundingBlockModelPlugin.register(config);
        RoundedWaterRenderHandler.register();
        RoundingBlockCommands.register(configPath);
    }
}
