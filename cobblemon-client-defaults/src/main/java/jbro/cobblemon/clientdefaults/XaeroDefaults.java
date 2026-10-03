package jbro.cobblemon.clientdefaults;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Xaero's preset is prepared using files only, before either map mod initializes. */
public final class XaeroDefaults {
    private static final String MODE = "xaero.mode";
    private static final String ICON_PACK = "E19-Xaero-Icons-1.5.1.zip";
    private static final List<String> MAPS = List.of("xaerominimap", "xaeroworldmap");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private XaeroDefaults() {}

    /** Absent mods have no files or completion records; all input files are prepared before writing. */
    public static synchronized int apply(Path gameDirectory, Path configDirectory, Set<String> installedMods) throws IOException {
        if (MAPS.stream().noneMatch(installedMods::contains)) return 0;
        ClientDefaults.ApplyMode mode = mode(configDirectory);
        Properties state = mode == ClientDefaults.ApplyMode.ONCE ? DefaultsFiles.readState(configDirectory) : new Properties();
        List<String> pending = MAPS.stream().filter(installedMods::contains)
            .filter(id -> mode == ClientDefaults.ApplyMode.ALWAYS || !"true".equals(state.getProperty(marker(id)))).toList();
        if (pending.isEmpty()) return 0;

        Map<Path, String> writes = new LinkedHashMap<>();
        boolean minimap = pending.contains("xaerominimap");
        boolean worldmap = pending.contains("xaeroworldmap");
        if (minimap) {
            Path file = configDirectory.resolve("xaero/minimap/profiles/default.cfg");
            String cfg = profile(file);
            for (String key : List.of("ignore_enforcement_if_edit_permission", "waypoints_in_world", "waypoints_on_minimap", "deathpoints"))
                cfg = setting(cfg, key, "false");
            cfg = setting(cfg, "minimap_shape", "1");
            writes.put(file, setting(cfg, "display_radar", "true"));
            Path hud = configDirectory.resolve("xaerohud.txt");
            writes.put(hud, topRightLayout(read(hud)));
            Path radar = configDirectory.resolve("xaero/minimap/default_radar_categories_client.json");
            writes.put(radar, radarDefaults(radar));
        }
        if (worldmap) {
            Path file = configDirectory.resolve("xaero/world-map/profiles/default.cfg");
            String cfg = profile(file);
            for (String key : List.of("ignore_enforcement_if_edit_permission", "waypoints", "render_waypoints", "map_teleport_allowed"))
                cfg = setting(cfg, key, "false");
            writes.put(file, setting(cfg, "display_minimap_radar", "true"));
        }

        Path optionsFile = gameDirectory.resolve("options.txt");
        String options = DefaultsFiles.seedOptions(read(optionsFile));
        boolean removeJourneyMap = !installedMods.contains("journeymap");
        if (removeJourneyMap) options = options.replaceAll("(?m)^key_key\\.journeymap\\.[^\\r\\n]*(?:\\r?\\n|\\r|$)", "");
        if (worldmap) options = option(options, "key_gui.xaero_open_map", "key.keyboard.j");
        if (minimap) {
            options = option(options, "key_gui.xaero_minimap_settings", "key.keyboard.y");
            for (String key : List.of("new_waypoint", "waypoints_key", "instant_waypoint", "toggle_waypoints", "toggle_map_waypoints", "switch_waypoint_set", "display_all_sets"))
                options = option(options, "key_gui.xaero_" + key, "key.keyboard.unknown");
        }
        options = packs(options, "resourcePacks", removeJourneyMap, minimap,
            Files.isRegularFile(gameDirectory.resolve("resourcepacks").resolve(ICON_PACK)));
        options = packs(options, "incompatibleResourcePacks", removeJourneyMap, false, false);
        writes.put(optionsFile, options);

        DefaultsFiles.ensureMode(configDirectory, MODE, mode);
        for (var write : writes.entrySet()) {
            if (!read(write.getKey()).equals(write.getValue())) DefaultsFiles.atomicWrite(write.getKey(), write.getValue());
        }
        if (mode == ClientDefaults.ApplyMode.ONCE)
            DefaultsFiles.markApplied(configDirectory, state, pending.stream().map(XaeroDefaults::marker).toList());
        return pending.size();
    }

    public static ClientDefaults.ApplyMode mode(Path configDirectory) throws IOException {
        return DefaultsFiles.mode(configDirectory, MODE, ClientDefaults.ApplyMode.ALWAYS);
    }

    public static void saveMode(Path configDirectory, ClientDefaults.ApplyMode mode) throws IOException {
        if (mode == null) throw new IOException("Xaero application mode is required");
        DefaultsFiles.saveModes(configDirectory, Map.of(MODE, mode));
    }

    private static String marker(String modId) { return "xaero-" + modId + "-v1"; }
    private static String read(Path file) throws IOException { return Files.exists(file) ? Files.readString(file) : ""; }
    private static String profile(Path file) throws IOException {
        String original = read(file);
        return original.isEmpty() ? "profile_name = Default\n" : original;
    }

    private static String setting(String original, String key, String value) {
        var matcher = Pattern.compile("(?m)^(\\h*" + Pattern.quote(key) + "\\h*=\\h*)([^\\r\\n#]*)(#[^\\r\\n]*)?").matcher(original);
        if (!matcher.find()) return append(original, key + " = " + value);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(2).trim().equals(value) ? match.group()
            : match.group(1) + value + (match.group(3) == null ? "" : " " + match.group(3))));
    }

    private static String option(String original, String key, String value) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":[^\\r\\n]*").matcher(original);
        return matcher.find() ? matcher.replaceAll(Matcher.quoteReplacement(key + ":" + value)) : append(original, key + ":" + value);
    }

    private static String append(String original, String line) {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        return original + (!original.isEmpty() && !original.endsWith("\n") && !original.endsWith("\r") ? newline : "") + line + newline;
    }

    private static String topRightLayout(String original) {
        var modules = Pattern.compile("(?m)^module;[^\\r\\n]*").matcher(original);
        var minimapId = Pattern.compile("(?:^|;)id=xaerominimap:minimap(?:;|$)");
        StringBuilder updated = new StringBuilder();
        boolean found = false;
        while (modules.find()) {
            String line = modules.group();
            if (minimapId.matcher(line).find()) {
                found = true;
                for (String field : List.of("x=0", "y=0", "centered=false", "fromRight=true", "fromBottom=false")) {
                    String key = field.substring(0, field.indexOf('='));
                    var value = Pattern.compile("(?<=;)" + Pattern.quote(key) + "=[^;]*").matcher(line);
                    line = value.find() ? value.replaceAll(Matcher.quoteReplacement(field))
                        : line + (line.endsWith(";") ? "" : ";") + field + ";";
                }
            }
            modules.appendReplacement(updated, Matcher.quoteReplacement(line));
        }
        modules.appendTail(updated);
        return found ? updated.toString() : append(original,
            "module;id=xaerominimap:minimap;x=0;y=0;centered=false;fromRight=true;fromBottom=false;flippedVer=false;flippedHor=false;");
    }

    private static String radarDefaults(Path file) throws IOException {
        String original = read(file);
        try (var input = XaeroDefaults.class.getResourceAsStream("/assets/cobblemon_client_defaults/xaero/default_radar_categories_client.json")) {
            if (input == null) throw new IOException("Missing Xaero radar defaults resource");
            JsonObject defaults = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject category = original.isBlank() ? defaults.deepCopy() : JsonParser.parseString(original).getAsJsonObject();
            boolean changed = original.isBlank();
            for (var field : defaults.entrySet()) {
                if (!category.has(field.getKey())) {
                    category.add(field.getKey(), field.getValue().deepCopy());
                    changed = true;
                }
            }
            JsonObject settings = category.getAsJsonObject("settingOverrides");
            JsonElement icons = settings.get("icons");
            if (icons == null || !icons.isJsonPrimitive() || !icons.getAsJsonPrimitive().isNumber() || icons.getAsDouble() != 2) {
                settings.addProperty("icons", 2);
                changed = true;
            }
            return changed ? JSON.toJson(category) + "\n" : original;
        } catch (RuntimeException exception) {
            throw new IOException("Could not read Xaero radar categories: " + file, exception);
        }
    }

    private static String packs(String original, String key, boolean removeJourneyMap, boolean selectIcons, boolean packExists) throws IOException {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":([^\\r\\n]*)").matcher(original);
        boolean present = matcher.find();
        if (!present && !(selectIcons && packExists)) return original;
        try {
            JsonArray before = present ? JsonParser.parseString(matcher.group(1)).getAsJsonArray() : new JsonArray();
            JsonArray after = new JsonArray();
            if (!present) { after.add("vanilla"); after.add("fabric"); }
            for (JsonElement entry : before) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Pack IDs must be strings");
                String id = entry.getAsString();
                if (removeJourneyMap && (id.equals("file/cc-journeymap-icons.zip") || id.equals("file/Maxi's-JM+CM_Pixel-U11.zip"))) continue;
                if (selectIcons && id.equals("file/" + ICON_PACK)) continue;
                after.add(entry);
            }
            if (selectIcons && packExists) after.add("file/" + ICON_PACK);
            return after.equals(before) ? original : option(original, key, after.toString());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Minecraft " + key + " list", exception);
        }
    }
}
