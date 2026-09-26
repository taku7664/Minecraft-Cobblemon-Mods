package jbro.cobblemon.bettermusic.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogCompiler;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;

public final class MusicResourcePackBuildTool {
    private static final String NAMESPACE = "cobleserver";
    private static final String MUSIC_PREFIX = "assets/cobleserver/sounds/music/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MusicResourcePackBuildTool() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected: <source-directory> <catalog-layout.json> <output-directory>");
        }
        build(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]));
    }

    public static void build(Path sourceDirectory, Path layoutFile, Path outputDirectory) throws IOException {
        sourceDirectory = requireDirectory(sourceDirectory, "sourceDirectory");
        layoutFile = requireFile(layoutFile, "layoutFile");
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        Path parent = outputDirectory.getParent();
        if (parent == null || outputDirectory.equals(parent) || outputDirectory.startsWith(sourceDirectory)) {
            throw new IOException("Unsafe resource-pack output directory: " + outputDirectory);
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".better-music-pack-");
        boolean published = false;
        try {
            generate(sourceDirectory, layoutFile, staging);
            if (Files.exists(outputDirectory)) {
                deleteTree(outputDirectory);
            }
            moveDirectory(staging, outputDirectory);
            published = true;
        } finally {
            if (!published) {
                deleteTree(staging);
            }
        }
    }

    private static void generate(Path sourceDirectory, Path layoutFile, Path staging) throws IOException {
        Path metadata = sourceDirectory.resolve("pack.mcmeta");
        if (!Files.isRegularFile(metadata)) {
            throw new IOException("Missing resource-pack metadata: " + metadata);
        }
        Files.copy(metadata, staging.resolve("pack.mcmeta"));

        Path soundRoot = sourceDirectory.resolve("assets/cobleserver/sounds");
        List<Path> oggFiles;
        try (var files = Files.walk(soundRoot)) {
            oggFiles = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".ogg"))
                .sorted(Comparator.comparing(path -> soundRoot.relativize(path).toString()))
                .toList();
        }
        if (oggFiles.isEmpty()) {
            throw new IOException("Resource pack contains no OGG files under " + soundRoot);
        }
        for (Path source : oggFiles) {
            validateOggVorbis(source, sourceDirectory.relativize(source).toString().replace('\\', '/'));
            Path target = staging.resolve(sourceDirectory.relativize(source).toString()).normalize();
            if (!target.startsWith(staging)) {
                throw new IOException("Resource-pack file escaped staging directory: " + source);
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }

        JsonObject catalog = readObject(layoutFile);
        JsonObject legacyAliases = catalog.has("legacyAliases")
            ? requiredObject(catalog, "legacyAliases", "catalog layout")
            : new JsonObject();
        catalog.remove("legacyAliases");
        JsonObject tracks = new JsonObject();
        JsonObject playlists = requiredObject(catalog, "playlists", "catalog layout");
        JsonObject sounds = new JsonObject();
        int musicCount = 0;
        for (Path source : oggFiles) {
            String relative = sourceDirectory.relativize(source).toString().replace('\\', '/');
            if (relative.startsWith(MUSIC_PREFIX)) {
                String soundPath = relative.substring("assets/cobleserver/sounds/".length(), relative.length() - 4);
                String trackPath = soundPath.substring("music/".length());
                String trackId = NAMESPACE + ":" + trackPath;
                String eventPath = "music.track." + trackPath.replace('/', '.');
                String eventId = NAMESPACE + ":" + eventPath;
                JsonObject track = new JsonObject();
                track.addProperty("event", eventId);
                track.addProperty("title", title(trackPath));
                JsonArray legacyPaths = new JsonArray();
                legacyPaths.add(trackPath + ".ogg");
                if (legacyAliases.has(trackId)) {
                    if (!legacyAliases.get(trackId).isJsonArray()) {
                        throw new IOException("legacyAliases." + trackId + " must be an array");
                    }
                    for (var alias : legacyAliases.getAsJsonArray(trackId)) {
                        legacyPaths.add(alias.getAsString());
                    }
                }
                track.add("legacyPaths", legacyPaths);
                tracks.add(trackId, track);
                sounds.add(eventPath, soundDefinition(NAMESPACE + ":" + soundPath, true));
                String implicitPlaylistId = NAMESPACE + ":track/" + trackPath;
                if (playlists.has(implicitPlaylistId)) {
                    throw new IOException("Catalog layout conflicts with implicit playlist " + implicitPlaylistId);
                }
                JsonObject implicitPlaylist = new JsonObject();
                JsonArray trackList = new JsonArray();
                trackList.add(trackId);
                implicitPlaylist.add("tracks", trackList);
                playlists.add(implicitPlaylistId, implicitPlaylist);
                musicCount++;
            }
        }
        if (musicCount == 0) {
            throw new IOException("Resource pack contains no music OGG files under " + MUSIC_PREFIX);
        }
        for (String trackId : legacyAliases.keySet()) {
            if (!tracks.has(trackId)) {
                throw new IOException("legacyAliases references unknown track " + trackId);
            }
        }
        addHitSound(sounds, sourceDirectory, "normal");
        addHitSound(sounds, sourceDirectory, "super_effective");
        addHitSound(sounds, sourceDirectory, "not_very_effective");
        addLowHpAlert(sounds, sourceDirectory);
        catalog.add("tracks", tracks);

        String catalogJson = GSON.toJson(catalog) + System.lineSeparator();
        var parsedCatalog = MusicCatalogParser.parse(new StringReader(catalogJson));
        MusicCatalogCompiler.compile(
            parsedCatalog.packId(),
            List.of(parsedCatalog),
            MusicCatalogSettings.defaults(parsedCatalog.packId()),
            MusicMappingOverrides.empty()
        );

        writeUtf8(staging.resolve("assets/cobleserver/sounds.json"), GSON.toJson(sounds) + System.lineSeparator());
        writeUtf8(
            staging.resolve("assets/better_cobblemon_music/catalogs/base/cobleserver.json"),
            catalogJson
        );
    }

    private static void addHitSound(JsonObject sounds, Path sourceDirectory, String name) throws IOException {
        Path source = sourceDirectory.resolve("assets/cobleserver/sounds/battle/hit/" + name + ".ogg");
        if (!Files.isRegularFile(source)) {
            throw new IOException("Missing hit sound: " + source);
        }
        sounds.add("battle.hit." + name, soundDefinition("cobleserver:battle/hit/" + name, false));
    }

    private static void addLowHpAlert(JsonObject sounds, Path sourceDirectory) throws IOException {
        Path source = sourceDirectory.resolve("assets/cobleserver/sounds/battle/low_hp/alert.ogg");
        if (!Files.isRegularFile(source)) {
            throw new IOException("Missing low-HP alert sound: " + source);
        }
        sounds.add("battle.low_hp.alert", soundDefinition("cobleserver:battle/low_hp/alert", false));
    }

    private static JsonObject soundDefinition(String name, boolean stream) {
        JsonObject sound = new JsonObject();
        sound.addProperty("name", name);
        if (stream) {
            sound.addProperty("stream", true);
        }
        JsonArray definitions = new JsonArray();
        definitions.add(sound);
        JsonObject event = new JsonObject();
        event.add("sounds", definitions);
        return event;
    }

    private static String title(String trackPath) {
        String fileName = trackPath.substring(trackPath.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder title = new StringBuilder(fileName.length());
        boolean capitalize = true;
        for (char character : fileName.toCharArray()) {
            if (character == ' ') {
                capitalize = true;
                title.append(character);
            } else if (capitalize) {
                title.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                title.append(character);
            }
        }
        return title.toString();
    }

    private static JsonObject readObject(Path path) throws IOException {
        try {
            var element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IOException("JSON root must be an object: " + path);
            }
            return element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse JSON " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static JsonObject requiredObject(JsonObject parent, String key, String owner) throws IOException {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IOException(owner + " requires object '" + key + "'");
        }
        return parent.getAsJsonObject(key);
    }

    private static void validateOggVorbis(Path source, String relativePath) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(source)) {
            prefix = input.readNBytes(65_536);
        }
        if (prefix.length < 4
            || prefix[0] != 'O'
            || prefix[1] != 'g'
            || prefix[2] != 'g'
            || prefix[3] != 'S'
            || !containsVorbisIdentification(prefix)) {
            throw new IOException("Resource-pack file is not Ogg/Vorbis: " + relativePath);
        }
    }

    private static boolean containsVorbisIdentification(byte[] bytes) {
        byte[] signature = new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'};
        outer:
        for (int index = 0; index <= bytes.length - signature.length; index++) {
            for (int offset = 0; offset < signature.length; offset++) {
                if (bytes[index + offset] != signature[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void writeUtf8(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static Path requireDirectory(Path value, String name) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException(name + " is not a directory: " + path);
        }
        return path;
    }

    private static Path requireFile(Path value, String name) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException(name + " is not a file: " + path);
        }
        return path;
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
