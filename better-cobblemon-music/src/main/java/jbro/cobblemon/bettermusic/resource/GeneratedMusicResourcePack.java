package jbro.cobblemon.bettermusic.resource;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.config.FieldMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class GeneratedMusicResourcePack {
    public static final String DIRECTORY_NAME = "better_cobblemon_music_generated";
    public static final String PACK_ID = "file/" + DIRECTORY_NAME;
    private static final String NAMESPACE = "better_cobblemon_music";

    private final Path musicDirectory;
    private final Path packDirectory;

    public GeneratedMusicResourcePack(Path configDirectory, Path resourcePackDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Objects.requireNonNull(resourcePackDirectory, "resourcePackDirectory");
        this.musicDirectory = configDirectory.resolve("music").toAbsolutePath().normalize();
        this.packDirectory = resourcePackDirectory.resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    public GenerationResult generate(BetterMusicConfigSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path soundRoot = packDirectory.resolve("assets").resolve(NAMESPACE).resolve("sounds").normalize();
        Files.createDirectories(soundRoot);

        JsonObject sounds = new JsonObject();
        List<String> missing = new ArrayList<>();
        for (String relativeTrack : configuredTracks(snapshot)) {
            Path source = musicDirectory.resolve(relativeTrack).normalize();
            if (!source.startsWith(musicDirectory) || !Files.isRegularFile(source)) {
                missing.add(relativeTrack);
                continue;
            }

            String withoutExtension = relativeTrack.substring(0, relativeTrack.length() - ".ogg".length());
            String soundPath = "custom/" + withoutExtension.toLowerCase(java.util.Locale.ROOT);
            Path target = soundRoot.resolve(soundPath + ".ogg").normalize();
            if (!target.startsWith(soundRoot)) {
                throw new IOException("Configured music path escapes the generated resource pack: " + relativeTrack);
            }
            Files.createDirectories(target.getParent());
            replaceWithLinkOrCopy(source, target);
            sounds.add(soundPath, streamingSound(soundPath));
        }

        writeUtf8(packDirectory.resolve("pack.mcmeta"), packMetadata());
        writeUtf8(
            packDirectory.resolve("assets").resolve(NAMESPACE).resolve("sounds.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(sounds) + System.lineSeparator()
        );
        return new GenerationResult(List.copyOf(missing), sounds.size());
    }

    public Path packDirectory() {
        return packDirectory;
    }

    private static Set<String> configuredTracks(BetterMusicConfigSnapshot snapshot) {
        Set<String> result = new LinkedHashSet<>();
        FieldMusicConfig field = snapshot.field();
        add(result, field.defaultPlaylist());
        field.dimensions().values().forEach(playlist -> add(result, playlist));
        field.biomes().values().forEach(playlist -> add(result, playlist));
        field.biomePathContains().values().forEach(playlist -> add(result, playlist));
        field.underground().ifPresent(playlist -> add(result, playlist));

        BattleMusicConfig battle = snapshot.battle();
        add(result, battle.wild());
        add(result, battle.trainer());
        add(result, battle.pvp());
        battle.content().values().forEach(playlist -> add(result, playlist));
        battle.roles().values().forEach(playlist -> add(result, playlist));
        battle.legendary().ifPresent(playlist -> add(result, playlist));
        battle.ultraBeast().ifPresent(playlist -> add(result, playlist));
        battle.pokemon().forEach(rule -> add(result, rule.playlist()));
        return result;
    }

    private static void add(Set<String> tracks, PlaylistDefinition playlist) {
        tracks.addAll(playlist.tracks());
    }

    private static JsonObject streamingSound(String soundPath) {
        JsonObject sound = new JsonObject();
        sound.addProperty("name", NAMESPACE + ":" + soundPath);
        sound.addProperty("stream", true);
        JsonArray definitions = new JsonArray();
        definitions.add(sound);
        JsonObject event = new JsonObject();
        event.add("sounds", definitions);
        return event;
    }

    private static String packMetadata() {
        return """
            {
              "pack": {
                "pack_format": 34,
                "description": "Generated by Better Cobblemon Music"
              }
            }
            """;
    }

    private static void replaceWithLinkOrCopy(Path source, Path target) throws IOException {
        Files.deleteIfExists(target);
        try {
            Files.createLink(target, source);
        } catch (UnsupportedOperationException | IOException linkFailure) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeUtf8(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record GenerationResult(List<String> missingTracks, int availableTracks) {
        public GenerationResult {
            missingTracks = List.copyOf(Objects.requireNonNull(missingTracks, "missingTracks"));
            if (availableTracks < 0) {
                throw new IllegalArgumentException("availableTracks must be non-negative");
            }
        }
    }
}
