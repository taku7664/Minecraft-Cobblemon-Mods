package jbro.cobblemon.bettermusic.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public final class BetterMusicConfigManager {
    private static final String DEFAULT_RESOURCE_ROOT =
        "/assets/better_cobblemon_music/config_defaults/";
    private static final String CONFIG_FILE = "music.json";
    private static final String MUSIC_DIRECTORY = "music";

    private final Path configDirectory;
    private volatile BetterMusicConfigSnapshot activeSnapshot;

    public BetterMusicConfigManager(Path configDirectory) {
        this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory").toAbsolutePath().normalize();
    }

    public synchronized ReloadResult initialize() {
        try {
            createMissingDefaults();
        } catch (IOException exception) {
            return activateBundledFallback(
                "Could not create missing configuration files in " + configDirectory + ": " + safeMessage(exception)
            );
        }
        return reload();
    }

    public synchronized ReloadResult reload() {
        try {
            BetterMusicConfigSnapshot candidate = loadFromDirectory();
            activeSnapshot = candidate;
            return new ReloadResult(
                Outcome.APPLIED,
                "Loaded Better Cobblemon Music configuration from " + configDirectory
            );
        } catch (ConfigReadException exception) {
            if (activeSnapshot != null) {
                return new ReloadResult(
                    Outcome.RETAINED_LAST_GOOD,
                    exception.getMessage() + "; retained the last known good configuration"
                );
            }
            return activateBundledFallback(exception.getMessage());
        }
    }

    public synchronized PreparedReload prepareReload() {
        try {
            BetterMusicConfigSnapshot candidate = loadFromDirectory();
            return new PreparedReload(
                Outcome.APPLIED,
                "Validated Better Cobblemon Music configuration from " + configDirectory,
                Optional.of(candidate)
            );
        } catch (ConfigReadException exception) {
            String suffix = activeSnapshot == null
                ? "; no configuration was activated"
                : "; retained the last known good configuration";
            return new PreparedReload(
                activeSnapshot == null ? Outcome.NO_VALID_CONFIG : Outcome.RETAINED_LAST_GOOD,
                exception.getMessage() + suffix,
                Optional.empty()
            );
        }
    }

    public synchronized void activate(BetterMusicConfigSnapshot snapshot) {
        activeSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public Optional<BetterMusicConfigSnapshot> activeSnapshot() {
        return Optional.ofNullable(activeSnapshot);
    }

    public Path configDirectory() {
        return configDirectory;
    }

    private ReloadResult activateBundledFallback(String userConfigFailure) {
        try {
            activeSnapshot = loadBundledDefaults();
            return new ReloadResult(
                Outcome.FALLBACK_TO_BUNDLED,
                userConfigFailure + "; using bundled defaults without overwriting user files"
            );
        } catch (ConfigReadException bundledFailure) {
            activeSnapshot = null;
            return new ReloadResult(
                Outcome.NO_VALID_CONFIG,
                userConfigFailure + "; bundled defaults also failed: " + bundledFailure.getMessage()
            );
        }
    }

    private BetterMusicConfigSnapshot loadFromDirectory() throws ConfigReadException {
        return snapshot(withFileReader(CONFIG_FILE, MusicConfigParser::parse));
    }

    private BetterMusicConfigSnapshot loadBundledDefaults() throws ConfigReadException {
        return snapshot(withBundledReader(CONFIG_FILE, MusicConfigParser::parse));
    }

    private static BetterMusicConfigSnapshot snapshot(MusicConfig config) {
        return new BetterMusicConfigSnapshot(config.playback(), config.field(), config.battle());
    }

    private <T> T withFileReader(String fileName, ReaderParser<T> parser) throws ConfigReadException {
        Path file = configDirectory.resolve(fileName);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parser.parse(reader);
        } catch (ConfigValidationException exception) {
            throw new ConfigReadException(fileName + ": " + exception.getMessage());
        } catch (IOException exception) {
            throw new ConfigReadException(fileName + ": " + safeMessage(exception));
        }
    }

    private <T> T withBundledReader(String fileName, ReaderParser<T> parser) throws ConfigReadException {
        InputStream stream = BetterMusicConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE_ROOT + fileName);
        if (stream == null) {
            throw new ConfigReadException(fileName + ": bundled default is missing");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parser.parse(reader);
        } catch (ConfigValidationException exception) {
            throw new ConfigReadException(fileName + ": " + exception.getMessage());
        } catch (IOException exception) {
            throw new ConfigReadException(fileName + ": " + safeMessage(exception));
        }
    }

    private void createMissingDefaults() throws IOException {
        Files.createDirectories(configDirectory);
        Files.createDirectories(configDirectory.resolve(MUSIC_DIRECTORY));
        Path target = configDirectory.resolve(CONFIG_FILE);
        if (!Files.exists(target)) {
            createDefaultFile(CONFIG_FILE, target);
        }
    }

    private void createDefaultFile(String fileName, Path target) throws IOException {
        Path temporary = Files.createTempFile(configDirectory, "." + fileName + ".", ".tmp");
        try {
            try (InputStream source = requiredBundledStream(fileName)) {
                Files.write(
                    temporary,
                    source.readAllBytes(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            }
            moveWithoutOverwrite(temporary, target);
        } catch (FileAlreadyExistsException ignored) {
            // Another initialization won the race. Its user-visible file is authoritative.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    private static InputStream requiredBundledStream(String fileName) throws IOException {
        InputStream stream = BetterMusicConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE_ROOT + fileName);
        if (stream == null) {
            throw new IOException("Bundled default is missing: " + fileName);
        }
        return stream;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface ReaderParser<T> {
        T parse(Reader reader);
    }

    private static final class ConfigReadException extends Exception {
        private ConfigReadException(String message) {
            super(message);
        }
    }

    public record ReloadResult(Outcome outcome, String message) {
        public ReloadResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }

        public boolean success() {
            return outcome == Outcome.APPLIED;
        }
    }

    public record PreparedReload(
        Outcome outcome,
        String message,
        Optional<BetterMusicConfigSnapshot> snapshot
    ) {
        public PreparedReload {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            if ((outcome == Outcome.APPLIED) != snapshot.isPresent()) {
                throw new IllegalArgumentException("Only an applied reload may contain a snapshot");
            }
        }

        public boolean success() {
            return outcome == Outcome.APPLIED;
        }
    }

    public enum Outcome {
        APPLIED,
        RETAINED_LAST_GOOD,
        FALLBACK_TO_BUNDLED,
        NO_VALID_CONFIG
    }
}
