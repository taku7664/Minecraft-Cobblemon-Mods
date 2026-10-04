package jbro.minecraft.roundingblock.client.settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
import jbro.minecraft.roundingblock.client.render.fluid.RoundedWaterRenderHandler;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional Mod Menu and Cloth Config integration. */
public final class RoundingBlockModMenu implements ModMenuApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("rounding-block.json");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RoundingBlockModMenu::createScreen;
    }

    private static Screen createScreen(Screen parent) {
        RoundingBlockConfig initial = RoundedBlockModel.currentConfig();
        AtomicReference<RoundingBlockConfig> edited = new AtomicReference<>(initial);
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(text("title"))
            .setSavingRunnable(() -> saveAndApply(initial, edited.get()));
        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory appearance = builder.getOrCreateCategory(text("category.appearance"));
        appearance.addEntry(entries.startBooleanToggle(text("enabled"), initial.enabled())
            .setDefaultValue(RoundingBlockConfig.defaults().enabled())
            .setTooltip(text("enabled.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> config.withEnabled(value)))
            .build());
        appearance.addEntry(entries.startDoubleField(text("radius"), initial.quality().radius())
            .setDefaultValue(RoundingBlockConfig.defaults().quality().radius())
            .setMin(RoundingBlockConfig.MIN_RADIUS)
            .setMax(RoundingBlockConfig.MAX_RADIUS)
            .setTooltip(text("radius.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> config.withRadius(value)))
            .build());
        appearance.addEntry(entries.startIntSlider(
                text("segments"),
                initial.quality().segments(),
                RoundingBlockConfig.MIN_SEGMENTS,
                RoundingBlockConfig.MAX_SEGMENTS
            )
            .setDefaultValue(RoundingBlockConfig.defaults().quality().segments())
            .setTooltip(text("segments.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> config.withSegments(value)))
            .build());

        ConfigCategory cache = builder.getOrCreateCategory(text("category.cache"));
        addMeshCacheEntry(cache, entries, edited, "fullBlockPlans", initial.cache().fullBlockPlans());
        addMeshCacheEntry(cache, entries, edited, "slabPlans", initial.cache().slabPlans());
        addMeshCacheEntry(cache, entries, edited, "complexShapePlans", initial.cache().complexShapePlans());
        addMeshCacheEntry(cache, entries, edited, "fluidContactPlans", initial.cache().fluidContactPlans());
        cache.addEntry(entries.startIntSlider(
                text("weightedModelVariants"),
                initial.cache().weightedModelVariants(),
                RoundingBlockConfig.MIN_APPEARANCE_CACHE,
                RoundingBlockConfig.MAX_APPEARANCE_CACHE
            )
            .setDefaultValue(RoundingBlockConfig.defaults().cache().weightedModelVariants())
            .setTooltip(text("weightedModelVariants.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> config.withWeightedModelVariants(value)))
            .build());

        ConfigCategory debug = builder.getOrCreateCategory(text("category.debug"));
        debug.addEntry(entries.startBooleanToggle(
                text("diagnosticLogging"),
                initial.debug().diagnosticLogging()
            )
            .setDefaultValue(RoundingBlockConfig.defaults().debug().diagnosticLogging())
            .setTooltip(text("diagnosticLogging.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> config.withDiagnosticLogging(value)))
            .build());
        return builder.build();
    }

    private static void addMeshCacheEntry(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        AtomicReference<RoundingBlockConfig> edited,
        String key,
        int initialValue
    ) {
        int defaultValue = switch (key) {
            case "fullBlockPlans" -> RoundingBlockConfig.defaults().cache().fullBlockPlans();
            case "slabPlans" -> RoundingBlockConfig.defaults().cache().slabPlans();
            case "complexShapePlans" -> RoundingBlockConfig.defaults().cache().complexShapePlans();
            case "fluidContactPlans" -> RoundingBlockConfig.defaults().cache().fluidContactPlans();
            default -> throw new IllegalArgumentException("Unknown cache key: " + key);
        };
        category.addEntry(entries.startIntSlider(
                text(key),
                initialValue,
                RoundingBlockConfig.MIN_MESH_CACHE,
                RoundingBlockConfig.MAX_MESH_CACHE
            )
            .setDefaultValue(defaultValue)
            .setTooltip(text(key + ".tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(config -> switch (key) {
                case "fullBlockPlans" -> config.withFullBlockPlans(value);
                case "slabPlans" -> config.withSlabPlans(value);
                case "complexShapePlans" -> config.withComplexShapePlans(value);
                case "fluidContactPlans" -> config.withFluidContactPlans(value);
                default -> throw new IllegalArgumentException("Unknown cache key: " + key);
            }))
            .build());
    }

    private static void saveAndApply(RoundingBlockConfig previous, RoundingBlockConfig updated) {
        if (updated.equals(previous)) {
            return;
        }
        try {
            updated.save(CONFIG_PATH);
        } catch (IOException exception) {
            LOGGER.error("Could not save Rounding-Block config from Mod Menu", exception);
            return;
        }

        RoundedBlockModel.PreparedRuntime previousRuntime = RoundedBlockModel.currentRuntimeSnapshot();
        Minecraft client = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> RoundedBlockModel.prepareConfig(updated))
            .whenComplete((prepared, preparationFailure) -> client.execute(() -> {
                if (preparationFailure != null) {
                    rollBack(previous, previousRuntime, preparationFailure);
                    return;
                }
                RoundedBlockModel.activatePreparedRuntime(prepared);
                RoundedWaterRenderHandler.setEnabled(updated.enabled());
                client.reloadResourcePacks().whenComplete((ignored, reloadFailure) -> {
                    if (reloadFailure != null) {
                        client.execute(() -> rollBack(previous, previousRuntime, reloadFailure));
                    }
                });
            }));
    }

    private static void rollBack(
        RoundingBlockConfig previous,
        RoundedBlockModel.PreparedRuntime previousRuntime,
        Throwable failure
    ) {
        RoundedBlockModel.activatePreparedRuntime(previousRuntime);
        RoundedWaterRenderHandler.setEnabled(previous.enabled());
        try {
            previous.save(CONFIG_PATH);
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        LOGGER.error("Could not apply Rounding-Block config from Mod Menu; restored previous settings", cause);
    }

    private static Component text(String suffix) {
        return Component.translatable("rounding_block.config." + suffix);
    }
}
