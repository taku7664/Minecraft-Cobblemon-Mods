package jbro.minecraft.roundingblock.client.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
import jbro.minecraft.roundingblock.client.render.fluid.RoundedWaterRenderHandler;
import jbro.minecraft.roundingblock.client.settings.RoundingBlockConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only settings commands. No server permission or packet is involved. */
public final class RoundingBlockCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final AtomicBoolean RELOAD_IN_PROGRESS = new AtomicBoolean();

    private RoundingBlockCommands() {
    }

    public static void register(Path configPath) {
        Path absoluteConfigPath = configPath.toAbsolutePath();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(build(absoluteConfigPath))
        );
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> build(Path configPath) {
        return literal("roundingblock")
                .then(literal("show").executes(context -> show(context.getSource())))
                .then(literal("reload").executes(context -> reloadFromDisk(context.getSource(), configPath)))
                .then(literal("enabled")
                    .then(argument("value", BoolArgumentType.bool()).executes(context -> update(
                        context.getSource(),
                        configPath,
                        config -> config.withEnabled(BoolArgumentType.getBool(context, "value"))
                    ))))
                .then(literal("quality")
                    .then(literal("radius")
                        .then(argument(
                            "value",
                            DoubleArgumentType.doubleArg(
                                RoundingBlockConfig.MIN_RADIUS,
                                RoundingBlockConfig.MAX_RADIUS
                            )
                        ).executes(context -> update(
                            context.getSource(),
                            configPath,
                            config -> config.withRadius(DoubleArgumentType.getDouble(context, "value"))
                        ))))
                    .then(literal("segments")
                        .then(argument(
                            "value",
                            IntegerArgumentType.integer(
                                RoundingBlockConfig.MIN_SEGMENTS,
                                RoundingBlockConfig.MAX_SEGMENTS
                            )
                        ).executes(context -> update(
                            context.getSource(),
                            configPath,
                            config -> config.withSegments(IntegerArgumentType.getInteger(context, "value"))
                        )))))
                .then(literal("cache")
                    .then(meshCacheCommand("fullBlockPlans", configPath, RoundingBlockConfig::withFullBlockPlans))
                    .then(meshCacheCommand("slabPlans", configPath, RoundingBlockConfig::withSlabPlans))
                    .then(meshCacheCommand(
                        "complexShapePlans",
                        configPath,
                        RoundingBlockConfig::withComplexShapePlans
                    ))
                    .then(meshCacheCommand(
                        "fluidContactPlans",
                        configPath,
                        RoundingBlockConfig::withFluidContactPlans
                    ))
                    .then(literal("weightedModelVariants")
                        .then(argument(
                            "value",
                            IntegerArgumentType.integer(
                                RoundingBlockConfig.MIN_APPEARANCE_CACHE,
                                RoundingBlockConfig.MAX_APPEARANCE_CACHE
                            )
                        ).executes(context -> update(
                            context.getSource(),
                            configPath,
                            config -> config.withWeightedModelVariants(
                                IntegerArgumentType.getInteger(context, "value")
                            )
                        )))))
                .then(literal("debug")
                    .then(literal("diagnosticLogging")
                        .then(argument("value", BoolArgumentType.bool()).executes(context -> update(
                            context.getSource(),
                            configPath,
                            config -> config.withDiagnosticLogging(BoolArgumentType.getBool(context, "value"))
                        ))))
            );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> meshCacheCommand(
        String name,
        Path configPath,
        IntConfigUpdate updater
    ) {
        return literal(name).then(argument(
            "value",
            IntegerArgumentType.integer(
                RoundingBlockConfig.MIN_MESH_CACHE,
                RoundingBlockConfig.MAX_MESH_CACHE
            )
        ).executes(context -> update(
            context.getSource(),
            configPath,
            config -> updater.apply(config, IntegerArgumentType.getInteger(context, "value"))
        )));
    }

    private static int show(FabricClientCommandSource source) {
        RoundingBlockConfig config = RoundedBlockModel.currentConfig();
        source.sendFeedback(Component.literal(
            "Rounding-Block: enabled=" + config.enabled()
                + ", radius=" + config.quality().radius()
                + ", segments=" + config.quality().segments()
                + ", caches=" + config.cache().fullBlockPlans()
                + "/" + config.cache().slabPlans()
                + "/" + config.cache().complexShapePlans()
                + "/" + config.cache().fluidContactPlans()
                + ", weighted=" + config.cache().weightedModelVariants()
                + ", diagnostics=" + config.debug().diagnosticLogging()
        ));
        return 1;
    }

    private static int update(
        FabricClientCommandSource source,
        Path configPath,
        UnaryOperator<RoundingBlockConfig> updater
    ) {
        if (!RELOAD_IN_PROGRESS.compareAndSet(false, true)) {
            source.sendError(Component.literal("Rounding-Block 설정을 이미 재적용하고 있습니다."));
            return 0;
        }
        RoundingBlockConfig previous = RoundedBlockModel.currentConfig();
        RoundingBlockConfig updated = updater.apply(previous);
        try {
            updated.save(configPath);
        } catch (IOException exception) {
            RELOAD_IN_PROGRESS.set(false);
            LOGGER.error("Could not save Rounding-Block config", exception);
            source.sendError(Component.literal("설정 파일 저장 실패: " + exception.getMessage()));
            return 0;
        }
        return applyAndReload(source, previous, updated, configPath, "설정을 저장하고 재적용했습니다.");
    }

    private static int reloadFromDisk(FabricClientCommandSource source, Path configPath) {
        if (!RELOAD_IN_PROGRESS.compareAndSet(false, true)) {
            source.sendError(Component.literal("Rounding-Block 설정을 이미 재적용하고 있습니다."));
            return 0;
        }
        List<String> warnings = new ArrayList<>();
        RoundingBlockConfig loaded = RoundingBlockConfig.load(configPath, warning -> {
            warnings.add(warning);
            LOGGER.warn(warning);
        });
        for (String warning : warnings) {
            source.sendError(Component.literal("설정 경고: " + warning));
        }
        return applyAndReload(
            source,
            RoundedBlockModel.currentConfig(),
            loaded,
            null,
            "JSON 설정을 다시 읽어 재적용했습니다."
        );
    }

    private static int applyAndReload(
        FabricClientCommandSource source,
        RoundingBlockConfig previous,
        RoundingBlockConfig next,
        Path rollbackConfigPath,
        String successMessage
    ) {
        Minecraft client = source.getClient();
        RoundedBlockModel.PreparedRuntime previousRuntime = RoundedBlockModel.currentRuntimeSnapshot();
        source.sendFeedback(Component.literal("Rounding-Block 템플릿과 캐시를 준비하는 중입니다..."));
        CompletableFuture.supplyAsync(() -> RoundedBlockModel.prepareConfig(next))
            .whenComplete((prepared, preparationFailure) -> client.execute(() -> {
                if (preparationFailure != null) {
                    finishFailure(
                        source,
                        previous,
                        previousRuntime,
                        rollbackConfigPath,
                        "설정 준비 실패",
                        preparationFailure
                    );
                    return;
                }
                try {
                    RoundedBlockModel.activatePreparedRuntime(prepared);
                    RoundedWaterRenderHandler.setEnabled(next.enabled());
                    client.reloadResourcePacks().whenComplete((ignored, reloadFailure) -> client.execute(() -> {
                        if (reloadFailure == null) {
                            RELOAD_IN_PROGRESS.set(false);
                            source.sendFeedback(Component.literal(successMessage));
                            return;
                        }
                        finishFailure(
                            source,
                            previous,
                            previousRuntime,
                            rollbackConfigPath,
                            "재적용 실패",
                            reloadFailure
                        );
                    }));
                    source.sendFeedback(Component.literal("Rounding-Block 모델을 다시 불러오는 중입니다..."));
                } catch (RuntimeException exception) {
                    finishFailure(
                        source,
                        previous,
                        previousRuntime,
                        rollbackConfigPath,
                        "재적용 시작 실패",
                        exception
                    );
                }
            }));
        return 1;
    }

    private static void finishFailure(
        FabricClientCommandSource source,
        RoundingBlockConfig previous,
        RoundedBlockModel.PreparedRuntime previousRuntime,
        Path rollbackConfigPath,
        String message,
        Throwable failure
    ) {
        RoundedBlockModel.activatePreparedRuntime(previousRuntime);
        RoundedWaterRenderHandler.setEnabled(previous.enabled());
        restorePreviousFile(previous, rollbackConfigPath);
        RELOAD_IN_PROGRESS.set(false);
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        LOGGER.error("Rounding-Block {}", message, cause);
        source.sendError(Component.literal(message + ": " + cause.getMessage()));
    }

    private static void restorePreviousFile(RoundingBlockConfig previous, Path configPath) {
        if (configPath == null) {
            return;
        }
        try {
            previous.save(configPath);
        } catch (IOException rollbackFailure) {
            LOGGER.error("Could not roll back Rounding-Block config file", rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface IntConfigUpdate {
        RoundingBlockConfig apply(RoundingBlockConfig config, int value);
    }
}
