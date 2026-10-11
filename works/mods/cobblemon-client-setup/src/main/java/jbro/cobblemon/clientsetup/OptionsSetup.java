package jbro.cobblemon.clientsetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Defaults the modpack would otherwise ship in options.txt and config/iris.properties, which would overwrite players'
 * own settings on every pack update: Korean, the pack's resource packs in order, no first-run prompts, and the LumaVale
 * shader on. They go only into a fresh install, told apart by options.txt not existing yet on the first run; a player
 * updating an existing profile keeps every setting. On every launch a resource pack or shader entry that names a file
 * of ours no longer installed is moved to the installed version, so pack updates keep working for both.
 */
public final class OptionsSetup {
    static final String OPTIONS_RULE = "options-defaults-v1";
    // v1 wrote optionsshaders.txt, which Iris never reads; v2 writes config/iris.properties.
    static final String SHADER_RULE = "shader-defaults-v2";
    /** Recorded on the first run: whether this profile started without options.txt. */
    static final String FRESH_INSTALL = "fresh-install";
    /**
     * The pack's resource packs, lowest priority first as options.txt lists them. Names are patterns so a new pack
     * version still matches; only packs present in resourcepacks/ are enabled.
     */
    static final List<Pattern> PACKS = Stream.of(
        "spawn-notification-ment\\.zip",
        "CCC_[^/]*\\.zip",
        "cobblemon-korean-translation-bundle-[^/]*\\.zip",
        "better-cobblemon-music-resourcepack-[^/]*\\.zip",
        "galmuri11-8px\\.zip",
        "Whimscape x Cobblemon [^/]*\\.zip",
        "Whimscape_[^/]*\\.zip",
        "RCT Trainers\\+ [^/]*\\.zip",
        "MoreRadicalTextures[^/]*\\.zip",
        "E19-Xaero-Icons-[^/]*\\.zip").map(Pattern::compile).toList();
    private static final Pattern LUMAVALE = Pattern.compile("LumaVale-([0-9]+(?:\\.[0-9]+)*)\\.zip");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+|[^0-9]+");

    private OptionsSetup() {}

    /** Returns how many one-time rules were applied this launch. */
    public static synchronized int apply(Path gameDirectory, Path configDirectory, Set<String> installedMods) throws IOException {
        Properties state = SetupFiles.readState(configDirectory);
        Path optionsFile = gameDirectory.resolve("options.txt");
        List<String> applied = new ArrayList<>();
        if (state.getProperty(FRESH_INSTALL) == null) {
            // Older setup versions already wrote options.txt before this rule existed, so a missing file here can
            // only be a profile that has never launched.
            boolean missing = !Files.exists(optionsFile) || Files.size(optionsFile) == 0;
            state.setProperty(FRESH_INSTALL, Boolean.toString(missing));
            applied.add(FRESH_INSTALL);
        }
        boolean fresh = "true".equals(state.getProperty(FRESH_INSTALL));
        if (!"true".equals(state.getProperty(OPTIONS_RULE))) {
            if (fresh) writeOptions(gameDirectory);
            else repairPacks(gameDirectory);
            applied.add(OPTIONS_RULE);
        } else {
            repairPacks(gameDirectory);
        }
        if (installedMods.contains("iris")) {
            Optional<String> shader = installedLumaVale(gameDirectory);
            if (shader.isPresent()) {
                if (!"true".equals(state.getProperty(SHADER_RULE))) {
                    if (fresh) writeShader(configDirectory, shader.get(), true);
                    else repairShader(configDirectory, gameDirectory, shader.get());
                    applied.add(SHADER_RULE);
                } else {
                    repairShader(configDirectory, gameDirectory, shader.get());
                }
            }
        }
        if (applied.isEmpty()) return 0;
        for (String rule : applied) if (!rule.equals(FRESH_INSTALL)) state.setProperty(rule, "true");
        SetupFiles.saveState(configDirectory, state);
        return (int) applied.stream().filter(rule -> !rule.equals(FRESH_INSTALL)).count();
    }

    private static void writeOptions(Path gameDirectory) throws IOException {
        Path file = gameDirectory.resolve("options.txt");
        String original = read(file);
        String options = SetupFiles.seedOptions(original);
        options = option(options, "lang", "ko_kr");
        options = option(options, "skipMultiplayerWarning", "true");
        options = option(options, "joinedFirstServer", "true");
        options = option(options, "tutorialStep", "none");
        options = packs(options, installedPacks(gameDirectory));
        if (!options.equals(original)) SetupFiles.atomicWrite(file, options);
    }

    /** The present file for each pattern in order; a pattern with several versions takes the newest. */
    static List<String> installedPacks(Path gameDirectory) throws IOException {
        List<String> files = packFiles(gameDirectory);
        List<String> packs = new ArrayList<>();
        for (Pattern pack : PACKS) newest(files, pack).ifPresent(packs::add);
        return packs;
    }

    private static List<String> packFiles(Path gameDirectory) throws IOException {
        Path directory = gameDirectory.resolve("resourcepacks");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
        }
    }

    /** The newest installed resource pack file matching the pattern. */
    static Optional<String> newestPack(Path gameDirectory, Pattern pack) throws IOException {
        return newest(packFiles(gameDirectory), pack);
    }

    private static Optional<String> newest(List<String> files, Pattern pack) {
        return files.stream().filter(name -> pack.matcher(name).matches()).max(OptionsSetup::compareNatural);
    }

    /** The pattern an enabled pack ID belongs to; folder packs count by their name plus .zip. */
    private static Optional<Pattern> ours(String id) {
        if (!id.startsWith("file/")) return Optional.empty();
        String name = id.substring(5);
        return PACKS.stream().filter(pack -> pack.matcher(name).matches() || pack.matcher(name + ".zip").matches()).findFirst();
    }

    /** Keeps the player's other packs and built-ins, drops other versions of ours, and appends ours in order. */
    static String packs(String original, List<String> installed) throws IOException {
        var matcher = Pattern.compile("(?m)^resourcePacks:([^\\r\\n]*)").matcher(original);
        boolean present = matcher.find();
        try {
            JsonArray before = present ? JsonParser.parseString(matcher.group(1)).getAsJsonArray() : new JsonArray();
            JsonArray after = new JsonArray();
            if (!present) { after.add("vanilla"); after.add("fabric"); }
            for (JsonElement entry : before) {
                if (ours(packId(entry)).isPresent()) continue;
                after.add(entry);
            }
            for (String pack : installed) after.add("file/" + pack);
            return after.equals(before) ? original : option(original, "resourcePacks", after.toString());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Minecraft resourcePacks list", exception);
        }
    }

    /**
     * After a pack update the old zip is gone and Minecraft would silently drop it. Swap each such entry of ours for
     * the installed version in the same place; entries that still exist, folder packs and the player's own packs stay.
     */
    static void repairPacks(Path gameDirectory) throws IOException {
        Path file = gameDirectory.resolve("options.txt");
        if (!Files.exists(file)) return;
        String original = Files.readString(file);
        var matcher = Pattern.compile("(?m)^resourcePacks:([^\\r\\n]*)").matcher(original);
        if (!matcher.find()) return;
        List<String> files = packFiles(gameDirectory);
        Path directory = gameDirectory.resolve("resourcepacks");
        try {
            JsonArray before = JsonParser.parseString(matcher.group(1)).getAsJsonArray();
            List<String> ids = new ArrayList<>();
            for (JsonElement entry : before) ids.add(packId(entry));
            JsonArray after = new JsonArray();
            for (String id : ids) {
                Optional<Pattern> pack = ours(id);
                if (pack.isEmpty() || Files.exists(directory.resolve(id.substring(5)))) {
                    after.add(id);
                    continue;
                }
                Optional<String> replacement = newest(files, pack.get()).map(name -> "file/" + name);
                if (replacement.isPresent() && !ids.contains(replacement.get()) && !after.contains(new JsonPrimitive(replacement.get()))) {
                    after.add(replacement.get());
                }
            }
            if (after.equals(before)) return;
            SetupFiles.atomicWrite(file, option(original, "resourcePacks", after.toString()));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Minecraft resourcePacks list", exception);
        }
    }

    private static String packId(JsonElement entry) {
        if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Pack IDs must be strings");
        return entry.getAsString();
    }

    /** The newest LumaVale zip in shaderpacks/, comparing version numbers rather than text. */
    static Optional<String> installedLumaVale(Path gameDirectory) throws IOException {
        Path directory = gameDirectory.resolve("shaderpacks");
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                .filter(name -> LUMAVALE.matcher(name).matches())
                .max(OptionsSetup::compareNatural);
        }
    }

    private static void repairShader(Path configDirectory, Path gameDirectory, String installed) throws IOException {
        String current = setting(read(irisFile(configDirectory)), "shaderPack");
        if (current == null || current.equals(installed) || !LUMAVALE.matcher(current).matches()) return;
        if (Files.exists(gameDirectory.resolve("shaderpacks").resolve(current))) return;
        writeShader(configDirectory, installed, false);
    }

    private static void writeShader(Path configDirectory, String pack, boolean enable) throws IOException {
        Path file = irisFile(configDirectory);
        String original = read(file);
        String updated = property(original, "shaderPack", pack);
        if (enable) updated = property(updated, "enableShaders", "true");
        if (!updated.equals(original)) SetupFiles.atomicWrite(file, updated);
    }

    private static Path irisFile(Path configDirectory) { return configDirectory.resolve("iris.properties"); }

    private static String setting(String text, String key) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\r\\n]*)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String property(String original, String key, String value) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=[^\\r\\n]*").matcher(original);
        return matcher.find() ? matcher.replaceAll(Matcher.quoteReplacement(key + "=" + value)) : append(original, key + "=" + value);
    }

    /** Orders names by their digit runs as numbers, so CCC_2.21 sorts after CCC_2.9. */
    static int compareNatural(String left, String right) {
        Matcher a = NUMBER.matcher(left);
        Matcher b = NUMBER.matcher(right);
        while (a.find() && b.find()) {
            String x = a.group();
            String y = b.group();
            boolean digits = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0));
            int difference = digits
                ? Comparator.<String>comparingInt(String::length).thenComparing(Comparator.naturalOrder())
                    .compare(x.replaceFirst("^0+(?=.)", ""), y.replaceFirst("^0+(?=.)", ""))
                : x.compareTo(y);
            if (difference != 0) return difference;
        }
        return Integer.compare(left.length(), right.length());
    }

    private static String read(Path file) throws IOException { return Files.exists(file) ? Files.readString(file) : ""; }

    private static String option(String original, String key, String value) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":[^\\r\\n]*").matcher(original);
        return matcher.find() ? matcher.replaceAll(Matcher.quoteReplacement(key + ":" + value)) : append(original, key + ":" + value);
    }

    private static String append(String original, String line) {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        return original + (!original.isEmpty() && !original.endsWith("\n") && !original.endsWith("\r") ? newline : "") + line + newline;
    }
}
