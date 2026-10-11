package jbro.cobblemon.clientsetup;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class ClientSetup {
    private static final String CLC_HUD_RULE = "clc-hud-v1";
    private static final String HUD_ENABLED = "client.hud.enabled";
    private static final String HUD_MODE = "clc_hud.mode";

    private ClientSetup() {}

    public enum Result { APPLIED, ALREADY_APPLIED, MOD_ABSENT }
    public enum ApplyMode { ONCE, ALWAYS }

    /** Preserve user modes and once-completion records when upgrading the old mod. */
    public static synchronized void migrateLegacyConfig(Path configDirectory) throws IOException {
        Path root = configDirectory.toAbsolutePath().normalize();
        Path legacy = root.resolve("cobblemon-client-defaults");
        Path current = SetupFiles.oldSetupDirectory(root);
        if (Files.exists(legacy)) {
            if (!Files.isDirectory(legacy) || Files.isSymbolicLink(legacy)) {
                throw new IOException("Legacy client setup config must be a directory: " + legacy);
            }
            if (Files.exists(current)) {
                throw new IOException("Both old and new client setup config directories exist; migration will not overwrite either");
            }
            Files.move(legacy, current);
        }
        Path target = SetupFiles.setupDirectory(root);
        // config/cobblemon-client-setup/ held the records and modes until 0.1.19; the old game-folder name held the
        // Xaero cache backups.
        moveInto(current, target);
        if (root.getParent() != null) moveInto(root.getParent().resolve("cobblemon_client_setup"), target);
    }

    /**
     * Moves a retired folder's contents into the profile's ppakemon_client_setup/ folder and removes it. A file already
     * in the new folder wins: a copy left in config/ then came back with a modpack update.
     */
    private static void moveInto(Path old, Path target) throws IOException {
        if (!Files.exists(old, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(old, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Old client setup folder must be a directory: " + old);
        }
        Files.createDirectories(target);
        List<Path> entries;
        try (var children = Files.list(old)) { entries = children.toList(); }
        for (Path entry : entries) {
            Path destination = target.resolve(entry.getFileName().toString());
            if (!Files.exists(destination)) {
                Files.move(entry, destination);
            } else if (Files.isDirectory(entry) && Files.isDirectory(destination)) {
                moveInto(entry, destination);
            } else if (!Files.isDirectory(entry)) {
                Files.delete(entry);
            }
        }
        try (var rest = Files.list(old)) { if (rest.findAny().isPresent()) return; }
        Files.delete(old);
    }

    public static synchronized Result apply(Path configDirectory, boolean clcInstalled) throws IOException {
        if (!clcInstalled) return Result.MOD_ABSENT;
        ApplyMode mode = mode(configDirectory);
        SetupFiles.ensureMode(configDirectory, HUD_MODE, mode);
        if (mode == ApplyMode.ALWAYS) {
            writeHud(configDirectory, false);
            return Result.APPLIED;
        }
        Properties state = SetupFiles.readState(configDirectory);
        if ("true".equals(state.getProperty(CLC_HUD_RULE))) return Result.ALREADY_APPLIED;
        writeHud(configDirectory, false);
        SetupFiles.markApplied(configDirectory, state, List.of(CLC_HUD_RULE));
        return Result.APPLIED;
    }

    public static boolean hudEnabled(Path configDirectory) throws IOException {
        Object enabled = readClient(configDirectory).get(HUD_ENABLED);
        return enabled == null || (Boolean) enabled;
    }

    public static ApplyMode mode(Path directory) throws IOException {
        return SetupFiles.mode(directory, HUD_MODE, ApplyMode.ALWAYS);
    }

    public static synchronized void saveMode(Path directory, ApplyMode mode) throws IOException {
        if (mode == null) throw new IOException("CLC HUD mode is required");
        SetupFiles.saveModes(directory, Map.of(HUD_MODE, mode));
    }

    public static synchronized void saveModes(Path directory, ApplyMode hudMode, ApplyMode keybindingsMode) throws IOException {
        if (hudMode == null || keybindingsMode == null) throw new IOException("Both application modes are required");
        SetupFiles.saveModes(directory, Map.of(HUD_MODE, hudMode, "keybindings.mode", keybindingsMode));
    }

    public static synchronized void saveModes(Path directory, ApplyMode hudMode, ApplyMode keybindingsMode, ApplyMode xaeroMode) throws IOException {
        if (hudMode == null || keybindingsMode == null || xaeroMode == null) throw new IOException("All application modes are required");
        SetupFiles.saveModes(directory, Map.of(HUD_MODE, hudMode, "keybindings.mode", keybindingsMode, "xaero.mode", xaeroMode));
    }

    private static void writeHud(Path directory, boolean enabled) throws IOException {
        CommentedConfig config = readClient(directory);
        if (Boolean.valueOf(enabled).equals(config.get(HUD_ENABLED))) return;
        try {
            config.set(HUD_ENABLED, enabled);
            SetupFiles.writeToml(clientFile(directory), config);
        } catch (RuntimeException exception) {
            throw new IOException("Could not update CLC client HUD setting", exception);
        }
    }

    private static CommentedConfig readClient(Path directory) throws IOException {
        Path file = clientFile(directory);
        CommentedConfig config = SetupFiles.readToml(file);
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

    private static Path clientFile(Path directory) {
        return directory.resolve("cobbled_level_control/client.toml");
    }

}
