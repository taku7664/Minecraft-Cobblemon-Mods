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
import java.util.Properties;

public final class ClientDefaults {
    private static final String CLC_HUD_RULE = "clc-hud-v1";
    private static final String HUD_ENABLED = "client.hud.enabled";
    private static final String HUD_MODE = "clc_hud.mode";

    private ClientDefaults() {}

    public enum Result { APPLIED, ALREADY_APPLIED, MOD_ABSENT }
    public enum ApplyMode { ONCE, ALWAYS }

    public static synchronized Result apply(Path configDirectory, boolean clcInstalled) throws IOException {
        if (!clcInstalled) return Result.MOD_ABSENT;
        ApplyMode mode = mode(configDirectory);
        if (!Files.exists(settingsFile(configDirectory))) saveMode(configDirectory, mode);
        if (mode == ApplyMode.ALWAYS) {
            writeHud(configDirectory, false);
            return Result.APPLIED;
        }
        Properties state = readState(configDirectory);
        if ("true".equals(state.getProperty(CLC_HUD_RULE))) return Result.ALREADY_APPLIED;
        writeHud(configDirectory, false);
        markApplied(configDirectory, state);
        return Result.APPLIED;
    }

    public static boolean hudEnabled(Path configDirectory) throws IOException {
        Object enabled = readClient(configDirectory).get(HUD_ENABLED);
        return enabled == null || (Boolean) enabled;
    }

    public static ApplyMode mode(Path directory) throws IOException {
        CommentedConfig config = readToml(settingsFile(directory));
        try {
            Object mode = config.get(HUD_MODE);
            if (mode == null) return ApplyMode.ALWAYS;
            if (mode instanceof String text) return ApplyMode.valueOf(text);
            throw new IllegalArgumentException("CLC HUD mode must be ONCE or ALWAYS");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid CLC HUD application mode", exception);
        }
    }

    public static synchronized void saveMode(Path directory, ApplyMode mode) throws IOException {
        if (mode == null) throw new IOException("CLC HUD mode is required");
        CommentedConfig config = readToml(settingsFile(directory));
        try {
            config.set(HUD_MODE, mode.name());
            atomicWrite(settingsFile(directory), new TomlWriter().writeToString(config));
        } catch (RuntimeException exception) {
            throw new IOException("Could not save client defaults application mode", exception);
        }
    }

    private static void writeHud(Path directory, boolean enabled) throws IOException {
        CommentedConfig config = readClient(directory);
        if (Boolean.valueOf(enabled).equals(config.get(HUD_ENABLED))) return;
        try {
            config.set(HUD_ENABLED, enabled);
            atomicWrite(clientFile(directory), new TomlWriter().writeToString(config));
        } catch (RuntimeException exception) {
            throw new IOException("Could not update CLC client HUD setting", exception);
        }
    }

    private static CommentedConfig readClient(Path directory) throws IOException {
        Path file = clientFile(directory);
        CommentedConfig config = readToml(file);
        try {
            for (String table : new String[] {"client", "client.hud"}) {
                Object value = config.get(table);
                if (value != null && !(value instanceof CommentedConfig)) {
                    throw new IOException("CLC " + table + " must be a TOML table");
                }
            }
            Object enabled = config.get(HUD_ENABLED);
            if (enabled != null && !(enabled instanceof Boolean)) {
                throw new IOException("CLC " + HUD_ENABLED + " must be a boolean");
            }
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Could not read CLC client config: " + file, exception);
        }
    }

    private static CommentedConfig readToml(Path file) throws IOException {
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

    private static Properties readState(Path directory) throws IOException {
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

    private static void markApplied(Path directory, Properties state) throws IOException {
        if ("true".equals(state.getProperty(CLC_HUD_RULE))) return;
        state.setProperty(CLC_HUD_RULE, "true");
        StringWriter text = new StringWriter();
        state.store(text, "Applied once; later launches preserve user settings");
        atomicWrite(stateFile(directory), text.toString());
    }

    private static Path clientFile(Path directory) {
        return directory.resolve("cobbled_level_control/client.toml");
    }

    private static Path stateFile(Path directory) {
        return directory.resolve("cobblemon-client-defaults/applied-defaults.properties");
    }

    private static Path settingsFile(Path directory) {
        return directory.resolve("cobblemon-client-defaults/client.toml");
    }

    private static void atomicWrite(Path file, String content) throws IOException {
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
