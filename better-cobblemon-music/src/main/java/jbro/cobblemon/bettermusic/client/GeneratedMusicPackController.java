package jbro.cobblemon.bettermusic.client;

import java.io.IOException;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.resource.GeneratedMusicResourcePack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class GeneratedMusicPackController {
    private final GeneratedMusicResourcePack generatedPack;
    private final Logger logger;

    public GeneratedMusicPackController(GeneratedMusicResourcePack generatedPack, Logger logger) {
        this.generatedPack = java.util.Objects.requireNonNull(generatedPack, "generatedPack");
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    public Optional<String> prepare(BetterMusicConfigSnapshot snapshot) {
        try {
            var result = generatedPack.generate(snapshot);
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
            return Optional.empty();
        } catch (IOException exception) {
            String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
            logger.error("Could not generate the Better Cobblemon Music resource pack", exception);
            return Optional.of(message);
        }
    }

    public void registerActivation() {
        ClientLifecycleEvents.CLIENT_STARTED.register(this::activateAndReload);
    }

    public Optional<String> prepareAndReload(BetterMusicConfigSnapshot snapshot) {
        Optional<String> failure = prepare(snapshot);
        if (failure.isPresent()) {
            return failure;
        }
        activateAndReload(Minecraft.getInstance());
        return Optional.empty();
    }

    private void activateAndReload(Minecraft client) {
        var repository = client.getResourcePackRepository();
        repository.reload();
        if (!repository.isAvailable(GeneratedMusicResourcePack.PACK_ID)) {
            logger.error("Generated music resource pack '{}' is not visible to Minecraft", generatedPack.packDirectory());
            return;
        }
        if (!repository.getSelectedIds().contains(GeneratedMusicResourcePack.PACK_ID)) {
            repository.addPack(GeneratedMusicResourcePack.PACK_ID);
            client.options.updateResourcePacks(repository);
        }
        client.reloadResourcePacks().exceptionally(exception -> {
            logger.error("Could not reload the generated Better Cobblemon Music resource pack", exception);
            return null;
        });
    }
}
