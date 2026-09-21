package jbro.cobblemon.bettermusic.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

public final class GlobalMusicSettingsStore {
    private GlobalMusicSettingsStore() {
    }

    public static GlobalMusicSettings load(Path configFile) throws IOException {
        String json = Files.readString(requireConfigFile(configFile), StandardCharsets.UTF_8);
        MusicConfig config = MusicConfigParser.parse(new StringReader(json));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        PlaybackSettings playback = config.playback();
        return new GlobalMusicSettings(
            playback.scanIntervalSeconds(),
            playback.fieldChangeDelaySeconds(),
            playback.betweenTracksSeconds(),
            playback.fadeInSeconds(),
            playback.fadeOutSeconds(),
            PlaylistDefinition.Selection.valueOf(
                root.get("selection").getAsString().toUpperCase(Locale.ROOT)
            ),
            root.get("volume").getAsDouble()
        );
    }

    public static void save(Path configFile, GlobalMusicSettings settings) throws IOException {
        configFile = requireConfigFile(configFile);
        Objects.requireNonNull(settings, "settings");
        JsonObject root = JsonParser.parseString(
            Files.readString(configFile, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        root.addProperty("scanIntervalSeconds", settings.scanIntervalSeconds());
        root.addProperty("fieldChangeDelaySeconds", settings.fieldChangeDelaySeconds());
        root.addProperty("betweenTracksSeconds", settings.betweenTracksSeconds());
        root.addProperty("fadeInSeconds", settings.fadeInSeconds());
        root.addProperty("fadeOutSeconds", settings.fadeOutSeconds());
        root.addProperty("selection", settings.selection().name().toLowerCase(Locale.ROOT));
        root.addProperty("volume", settings.volume());

        String candidate = new GsonBuilder().setPrettyPrinting().create().toJson(root)
            + System.lineSeparator();
        MusicConfigParser.parse(new StringReader(candidate));
        writeAtomically(configFile, candidate);
    }

    private static Path requireConfigFile(Path configFile) {
        return Objects.requireNonNull(configFile, "configFile").toAbsolutePath().normalize();
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Configuration file has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".music.json.", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
