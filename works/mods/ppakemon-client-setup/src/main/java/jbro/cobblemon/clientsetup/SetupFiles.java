package jbro.cobblemon.clientsetup;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** File persistence shared by the independent launch rules. */
final class SetupFiles {
    private SetupFiles() {}

    // Minecraft 1.21.1: unversioned modern keys enter the legacy numeric-key migration.
    static String seedOptions(String original) { return original.isEmpty() ? "version:3955\n" : original; }

    static ClientSetup.ApplyMode mode(Path directory, String key, ClientSetup.ApplyMode fallback) throws IOException {
        try {
            Object mode = readToml(settingsFile(directory)).get(key);
            if (mode == null) return fallback;
            if (mode instanceof String text) return ClientSetup.ApplyMode.valueOf(text);
            throw new IllegalArgumentException("Mode must be ONCE or ALWAYS");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid client setup mode: " + key, exception);
        }
    }

    static void ensureMode(Path directory, String key, ClientSetup.ApplyMode mode) throws IOException {
        if (!readToml(settingsFile(directory)).contains(key)) saveModes(directory, Map.of(key, mode));
    }

    static synchronized void saveModes(Path directory, Map<String, ClientSetup.ApplyMode> modes) throws IOException {
        CommentedConfig config = readToml(settingsFile(directory));
        try {
            for (var entry : modes.entrySet()) config.set(entry.getKey(), entry.getValue().name());
            writeToml(settingsFile(directory), config);
        } catch (RuntimeException exception) {
            throw new IOException("Could not save client setup application modes", exception);
        }
    }

    static CommentedConfig readToml(Path file) throws IOException {
        CommentedConfig config = CommentedConfig.of(LinkedHashMap::new, TomlFormat.instance());
        try {
            if (Files.exists(file)) {
                new TomlParser().parse(new StringReader(Files.readString(file)), config, ParsingMode.REPLACE);
            }
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Could not read TOML config: " + file, exception);
        }
    }

    static void writeToml(Path file, CommentedConfig config) throws IOException {
        atomicWrite(file, new TomlWriter().writeToString(config));
    }

    /**
     * The completion record of this profile. A record written in another profile (a modpack exported with the
     * author's record, or one from before records named their profile) says nothing about this one, so its rules
     * start over here.
     */
    static Properties readState(Path directory) throws IOException {
        Properties state = readRawState(directory);
        String profile = profileId(directory);
        if (profile.equals(state.getProperty(PROFILE))) return state;
        Properties own = new Properties();
        own.setProperty(PROFILE, profile);
        return own;
    }

    static final String PROFILE = "profile";

    /** A short hash of the config directory's absolute path, so the record does not carry the user name. */
    static String profileId(Path directory) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(directory.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The record file as written, whichever profile wrote it. */
    static Properties readRawState(Path directory) throws IOException {
        Properties state = new Properties();
        Path file = stateFile(directory);
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                state.load(reader);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Could not read applied client setup: " + file, exception);
            }
        }
        return state;
    }

    static void markApplied(Path directory, Properties state, Iterable<String> rules) throws IOException {
        boolean changed = false;
        for (String rule : rules) {
            if (!"true".equals(state.getProperty(rule))) {
                state.setProperty(rule, "true");
                changed = true;
            }
        }
        if (changed) saveState(directory, state);
    }

    static void saveState(Path directory, Properties state) throws IOException {
        StringWriter text = new StringWriter();
        state.store(text, "Applied once; later launches preserve user settings");
        atomicWrite(stateFile(directory), text.toString());
    }

    private static Path stateFile(Path directory) {
        return setupDirectory(directory).resolve("applied-defaults.properties");
    }

    private static Path settingsFile(Path directory) {
        return setupDirectory(directory).resolve("client.toml");
    }

    /**
     * The hook's own files live beside config/, in the profile's ppakemon_client_setup/ folder, so a modpack exported
     * with the whole config folder does not carry the author's records and modes.
     */
    static Path setupDirectory(Path configDirectory) {
        Path game = configDirectory.toAbsolutePath().normalize().getParent();
        return game == null ? configDirectory.resolve("ppakemon_client_setup") : game.resolve("ppakemon_client_setup");
    }

    /** Where 0.1.19 and earlier kept those files. */
    static Path oldSetupDirectory(Path configDirectory) {
        return configDirectory.toAbsolutePath().normalize().resolve("cobblemon-client-setup");
    }

    static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".client-setup-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
