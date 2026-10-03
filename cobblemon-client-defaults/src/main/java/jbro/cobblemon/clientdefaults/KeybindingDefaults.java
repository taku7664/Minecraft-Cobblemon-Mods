package jbro.cobblemon.clientdefaults;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies the approved key cleanup before Minecraft reads options.txt. */
public final class KeybindingDefaults {
    private static final String MODE = "keybindings.mode";
    private static final List<Rule> RULES = List.of(
        new Rule("cobbled_level_control", List.of("key_key.cobbled_level_control.toggle_hud")),
        new Rule("talkingheads", List.of("key_talkingheads.keybinding.modToggle")),
        new Rule("voicechat", List.of("key_key.hide_icons")),
        new Rule("zoomify", List.of("key_zoomify.key.zoom.secondary")),
        new Rule("craftingtweaks", List.of("key_key.craftingtweaks.compress_stack", "key_key.craftingtweaks.refill_last_stack")));

    private KeybindingDefaults() {}

    private record Rule(String modId, List<String> keys) {
        String marker() { return "keybindings-" + modId + "-v1"; }
    }

    /** Returns the number of mod presets applied; absent mods never receive completion records. */
    public static synchronized int apply(Path gameDirectory, Path configDirectory, Set<String> installedMods) throws IOException {
        if (RULES.stream().noneMatch(rule -> installedMods.contains(rule.modId()))) return 0;
        ClientDefaults.ApplyMode mode = mode(configDirectory);
        DefaultsFiles.ensureMode(configDirectory, MODE, mode);
        Properties state = mode == ClientDefaults.ApplyMode.ONCE ? DefaultsFiles.readState(configDirectory) : new Properties();
        List<Rule> pending = RULES.stream().filter(rule -> installedMods.contains(rule.modId()))
            .filter(rule -> mode == ClientDefaults.ApplyMode.ALWAYS || !"true".equals(state.getProperty(rule.marker()))).toList();
        if (pending.isEmpty()) return 0;

        Path optionsFile = gameDirectory.resolve("options.txt");
        String original = Files.exists(optionsFile) ? Files.readString(optionsFile) : "";
        String updated = clearKeys(DefaultsFiles.seedOptions(original), pending);
        Path craftingFile = configDirectory.resolve("craftingtweaks-common.toml");
        // Validate every pending file before changing options, so invalid TOML cannot partially clear keys.
        CommentedConfig crafting = pending.stream().anyMatch(rule -> rule.modId().equals("craftingtweaks"))
            ? craftingButtons(craftingFile) : null;
        if (!original.equals(updated)) DefaultsFiles.atomicWrite(optionsFile, updated);
        if (crafting != null) DefaultsFiles.writeToml(craftingFile, crafting);
        if (mode == ClientDefaults.ApplyMode.ONCE) {
            DefaultsFiles.markApplied(configDirectory, state, pending.stream().map(Rule::marker).toList());
        }
        return pending.size();
    }

    public static ClientDefaults.ApplyMode mode(Path configDirectory) throws IOException {
        return DefaultsFiles.mode(configDirectory, MODE, ClientDefaults.ApplyMode.ONCE);
    }

    public static synchronized void saveMode(Path configDirectory, ClientDefaults.ApplyMode mode) throws IOException {
        if (mode == null) throw new IOException("Keybinding application mode is required");
        DefaultsFiles.saveModes(configDirectory, Map.of(MODE, mode));
    }

    private static String clearKeys(String original, List<Rule> pending) {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String updated = original;
        for (Rule rule : pending) {
            for (String key : rule.keys()) {
                var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":[^\\r\\n]*").matcher(updated);
                String cleared = key + ":key.keyboard.unknown";
                if (matcher.find()) {
                    updated = matcher.replaceAll(cleared);
                } else {
                    if (!updated.isEmpty() && !updated.endsWith("\n") && !updated.endsWith("\r")) updated += newline;
                    updated += cleared + newline;
                }
            }
        }
        return updated;
    }

    private static CommentedConfig craftingButtons(Path file) throws IOException {
        CommentedConfig config = DefaultsFiles.readToml(file);
        try {
            Object client = config.get("client");
            if (client != null && !(client instanceof CommentedConfig)) throw new IOException("Crafting Tweaks client must be a TOML table");
            Object mode = config.get("client.mode");
            if (mode != null && !(mode instanceof String)) throw new IOException("Crafting Tweaks client.mode must be a string");
            if ("BUTTONS".equals(mode)) return null;
            config.set("client.mode", "BUTTONS");
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Could not update Crafting Tweaks client mode", exception);
        }
    }
}
