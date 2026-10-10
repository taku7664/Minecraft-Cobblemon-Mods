package jbro.cobblemon.clientsetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
 * First-launch defaults the modpack would otherwise ship in options.txt and optionsshaders.txt, which would overwrite
 * players' own settings on every pack update: Korean, the pack's resource packs in order, no first-run prompts, and the
 * LumaVale shader on. Each is applied once; afterwards the player's choices stand. A shader setting that points at a
 * LumaVale file no longer installed is moved to the installed one on every launch, so a shader update keeps working.
 */
public final class OptionsSetup {
    static final String OPTIONS_RULE = "options-defaults-v1";
    static final String SHADER_RULE = "shader-defaults-v1";
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

    private OptionsSetup() {}

    /** Returns how many one-time rules were applied this launch. */
    public static synchronized int apply(Path gameDirectory, Path configDirectory, Set<String> installedMods) throws IOException {
        Properties state = SetupFiles.readState(configDirectory);
        List<String> applied = new ArrayList<>();
        if (!"true".equals(state.getProperty(OPTIONS_RULE))) {
            writeOptions(gameDirectory);
            applied.add(OPTIONS_RULE);
        }
        if (installedMods.contains("iris")) {
            Optional<String> shader = installedLumaVale(gameDirectory);
            if (shader.isPresent()) {
                if (!"true".equals(state.getProperty(SHADER_RULE))) {
                    writeShader(gameDirectory, shader.get(), true);
                    applied.add(SHADER_RULE);
                } else {
                    repairShader(gameDirectory, shader.get());
                }
            }
        }
        if (!applied.isEmpty()) SetupFiles.markApplied(configDirectory, state, applied);
        return applied.size();
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

    /** The present file for each pattern in order; a pattern with several versions takes the newest name. */
    static List<String> installedPacks(Path gameDirectory) throws IOException {
        Path directory = gameDirectory.resolve("resourcepacks");
        if (!Files.isDirectory(directory)) return List.of();
        List<String> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().toList();
        }
        List<String> packs = new ArrayList<>();
        for (Pattern pack : PACKS) {
            files.stream().filter(name -> pack.matcher(name).matches()).reduce((first, second) -> second).ifPresent(packs::add);
        }
        return packs;
    }

    /** Keeps the player's other packs and built-ins, drops stale versions of ours, and appends ours in order. */
    static String packs(String original, List<String> installed) throws IOException {
        var matcher = Pattern.compile("(?m)^resourcePacks:([^\\r\\n]*)").matcher(original);
        boolean present = matcher.find();
        try {
            JsonArray before = present ? JsonParser.parseString(matcher.group(1)).getAsJsonArray() : new JsonArray();
            JsonArray after = new JsonArray();
            if (!present) { after.add("vanilla"); after.add("fabric"); }
            for (JsonElement entry : before) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Pack IDs must be strings");
                String id = entry.getAsString();
                if (id.startsWith("file/") && PACKS.stream().anyMatch(pack -> pack.matcher(id.substring(5)).matches())) continue;
                after.add(entry);
            }
            for (String pack : installed) after.add("file/" + pack);
            return after.equals(before) ? original : option(original, "resourcePacks", after.toString());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Minecraft resourcePacks list", exception);
        }
    }

    /** The newest LumaVale zip in shaderpacks/, comparing version numbers rather than text. */
    static Optional<String> installedLumaVale(Path gameDirectory) throws IOException {
        Path directory = gameDirectory.resolve("shaderpacks");
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                .filter(name -> LUMAVALE.matcher(name).matches())
                .max(Comparator.comparing(OptionsSetup::version, OptionsSetup::compareVersions));
        }
    }

    private static void repairShader(Path gameDirectory, String installed) throws IOException {
        String current = shaderSetting(read(gameDirectory.resolve("optionsshaders.txt")), "shaderPack");
        if (current == null || current.equals(installed) || !LUMAVALE.matcher(current).matches()) return;
        if (Files.exists(gameDirectory.resolve("shaderpacks").resolve(current))) return;
        writeShader(gameDirectory, installed, false);
    }

    private static void writeShader(Path gameDirectory, String pack, boolean enable) throws IOException {
        Path file = gameDirectory.resolve("optionsshaders.txt");
        String original = read(file);
        String updated = shaderOption(original, "shaderPack", pack);
        if (enable) updated = shaderOption(updated, "enableShaders", "true");
        if (!updated.equals(original)) SetupFiles.atomicWrite(file, updated);
    }

    private static String shaderSetting(String text, String key) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\r\\n]*)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String shaderOption(String original, String key, String value) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=[^\\r\\n]*").matcher(original);
        return matcher.find() ? matcher.replaceAll(Matcher.quoteReplacement(key + "=" + value)) : append(original, key + "=" + value);
    }

    private static int[] version(String name) {
        var matcher = LUMAVALE.matcher(name);
        if (!matcher.matches()) return new int[0];
        return Stream.of(matcher.group(1).split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static int compareVersions(int[] left, int[] right) {
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int difference = Integer.compare(index < left.length ? left[index] : 0, index < right.length ? right[index] : 0);
            if (difference != 0) return difference;
        }
        return 0;
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
