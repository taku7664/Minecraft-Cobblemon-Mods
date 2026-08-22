package jbro.cobblemon.bettermusic.client;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.resource.GeneratedMusicResourcePack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class GeneratedMusicPackController {
    private final GeneratedMusicResourcePack generatedPack;
    private final Logger logger;
    private boolean reloadInProgress;

    public GeneratedMusicPackController(GeneratedMusicResourcePack generatedPack, Logger logger) {
        this.generatedPack = java.util.Objects.requireNonNull(generatedPack, "generatedPack");
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    public Optional<String> prepare(BetterMusicConfigSnapshot snapshot) {
        try (var prepared = generatedPack.prepare(snapshot); var published = prepared.publish()) {
            logGeneration(prepared.result());
            published.commit();
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            String message = safeMessage(exception);
            logger.error("Could not generate the Better Cobblemon Music resource pack", exception);
            return Optional.of(message);
        }
    }

    public void registerActivation() {
        ClientLifecycleEvents.CLIENT_STARTED.register(this::activateAndReload);
    }

    public synchronized CompletableFuture<Optional<String>> prepareAndReload(
        BetterMusicConfigSnapshot snapshot,
        Runnable activateSnapshot
    ) {
        java.util.Objects.requireNonNull(activateSnapshot, "activateSnapshot");
        if (reloadInProgress) {
            return CompletableFuture.completedFuture(Optional.of("A Better Cobblemon Music reload is already running"));
        }
        reloadInProgress = true;

        GeneratedMusicResourcePack.PublishedPack published;
        try (var prepared = generatedPack.prepare(snapshot)) {
            logGeneration(prepared.result());
            published = prepared.publish();
        } catch (IOException | RuntimeException exception) {
            reloadInProgress = false;
            logger.error("Could not prepare the Better Cobblemon Music resource pack", exception);
            return CompletableFuture.completedFuture(Optional.of(safeMessage(exception)));
        }

        Minecraft client = Minecraft.getInstance();
        CompletableFuture<Optional<String>> result = safelyRefreshAndReload(client, true)
            .handleAsync((ignored, reloadFailure) -> {
                if (reloadFailure == null) {
                    try {
                        activateSnapshot.run();
                        published.commit();
                    } catch (IOException cleanupFailure) {
                        logger.warn("Could not remove the previous generated music pack backup", cleanupFailure);
                    } catch (RuntimeException activationFailure) {
                        reloadFailure = activationFailure;
                    }
                    if (reloadFailure == null) {
                        return CompletableFuture.completedFuture(Optional.<String>empty());
                    }
                }
                String failureMessage = safeMessage(unwrap(reloadFailure));
                try {
                    published.rollback();
                } catch (IOException rollbackFailure) {
                    logger.error("Could not restore the previous generated music pack", rollbackFailure);
                    return CompletableFuture.completedFuture(Optional.of(
                        failureMessage + "; rollback also failed: " + safeMessage(rollbackFailure)
                    ));
                }
                return safelyRefreshAndReload(client, false)
                    .handle((restoreIgnored, restoreFailure) -> {
                        if (restoreFailure != null) {
                            logger.error("Could not reload the restored generated music pack", restoreFailure);
                            return Optional.of(
                                failureMessage + "; restored files but Minecraft resource restoration failed: "
                                    + safeMessage(unwrap(restoreFailure))
                            );
                        }
                        return Optional.of(failureMessage + "; restored the previous generated music pack");
                    });
            }, client)
            .thenCompose(future -> future);
        return result.whenComplete((ignored, failure) -> {
            synchronized (GeneratedMusicPackController.this) {
                reloadInProgress = false;
            }
        });
    }

    private void activateAndReload(Minecraft client) {
        safelyRefreshAndReload(client, true).exceptionally(exception -> {
            logger.error("Could not reload the generated Better Cobblemon Music resource pack", exception);
            return null;
        });
    }

    private CompletableFuture<Void> safelyRefreshAndReload(Minecraft client, boolean requireGeneratedPack) {
        try {
            return refreshAndReload(client, requireGeneratedPack);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Void> refreshAndReload(Minecraft client, boolean requireGeneratedPack) {
        var repository = client.getResourcePackRepository();
        repository.reload();
        if (!repository.isAvailable(GeneratedMusicResourcePack.PACK_ID)) {
            if (requireGeneratedPack) {
                return CompletableFuture.failedFuture(new IOException(
                    "Generated music resource pack is not visible to Minecraft: " + generatedPack.packDirectory()
                ));
            }
            if (repository.getSelectedIds().contains(GeneratedMusicResourcePack.PACK_ID)) {
                repository.removePack(GeneratedMusicResourcePack.PACK_ID);
                client.options.updateResourcePacks(repository);
            }
            return client.reloadResourcePacks();
        }
        if (!repository.getSelectedIds().contains(GeneratedMusicResourcePack.PACK_ID)) {
            repository.addPack(GeneratedMusicResourcePack.PACK_ID);
            client.options.updateResourcePacks(repository);
        }
        return client.reloadResourcePacks();
    }

    private void logGeneration(GeneratedMusicResourcePack.GenerationResult result) {
        if (!result.missingTracks().isEmpty()) {
            logger.warn(
                "Generated music pack contains {} tracks; {} configured files are missing under config/better_cobblemon_music/music: {}",
                result.availableTracks(),
                result.missingTracks().size(),
                result.missingTracks()
            );
        } else {
            logger.info("Generated music resource pack with {} tracks", result.availableTracks());
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
