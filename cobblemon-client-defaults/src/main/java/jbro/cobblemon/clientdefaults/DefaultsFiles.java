package jbro.cobblemon.clientdefaults;

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
final class DefaultsFiles {
    private DefaultsFiles() {}

    static ClientDefaults.ApplyMode mode(Path directory, String key, ClientDefaults.ApplyMode fallback) throws IOException {
        try {
            Object mode = readToml(settingsFile(directory)).get(key);
            if (mode == null) return fallback;
            if (mode instanceof String text) return ClientDefaults.ApplyMode.valueOf(text);
            throw new IllegalArgumentException("Mode must be ONCE or ALWAYS");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid client defaults mode: " + key, exception);
        }
    }

    static void ensureMode(Path directory, String key, ClientDefaults.ApplyMode mode) throws IOException {
        if (!readToml(settingsFile(directory)).contains(key)) saveModes(directory, Map.of(key, mode));
    }

    static synchronized void saveModes(Path directory, Map<String, ClientDefaults.ApplyMode> modes) throws IOException {
        CommentedConfig config = readToml(settingsFile(directory));
        try {
            for (var entry : modes.entrySet()) config.set(entry.getKey(), entry.getValue().name());
            writeToml(settingsFile(directory), config);
        } catch (RuntimeException exception) {
            throw new IOException("Could not save client defaults application modes", exception);
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

    static Properties readState(Path directory) throws IOException {
        Properties state = new Properties();
        Path file = stateFile(directory);
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                state.load(reader);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Could not read applied client defaults: " + file, exception);
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
        if (!changed) return;
        StringWriter text = new StringWriter();
        state.store(text, "Applied once; later launches preserve user settings");
        atomicWrite(stateFile(directory), text.toString());
    }

    private static Path stateFile(Path directory) {
        return directory.resolve("cobblemon-client-defaults/applied-defaults.properties");
    }

    private static Path settingsFile(Path directory) {
        return directory.resolve("cobblemon-client-defaults/client.toml");
    }

    static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".client-defaults-", ".tmp");
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
