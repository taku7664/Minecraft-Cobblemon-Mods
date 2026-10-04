package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
final class MusicExtensionTitleTemplateTest {
    @TempDir Path directory;

    @Test
    void windowsTemplatePreservesUnicodeTitlesAcrossRebuildsAndRejectsInvalidInputBeforeWriting() throws Exception {
        Path module = Files.isDirectory(Path.of("extension-template")) ? Path.of(".") : Path.of("mods/better-cobblemon-music");
        Path script = directory.resolve("update-music.ps1");
        Files.copy(module.resolve("extension-template/my-music-pack/update-music.ps1"), script);
        Path audio = directory.resolve("assets/mymusic/sounds/music/a.ogg");
        Files.createDirectories(audio.getParent());
        Files.write(audio, new byte[]{'O', 'g', 'g', 'S'});
        Path titles = directory.resolve("track-titles.json");
        var input = new com.google.gson.JsonObject();
        input.addProperty("mymusic:a", "한글 곡명 \"Forest\"\\Mix");
        Files.writeString(titles, input.toString());
        for (int iteration = 0; iteration < 2; iteration++) {
            assertEquals(0, update(script));
            var catalog = JsonParser.parseString(Files.readString(directory.resolve(
                "assets/better_cobblemon_music/catalogs/extensions/mymusic.json"))).getAsJsonObject();
            assertEquals(input.get("mymusic:a").getAsString(), catalog.getAsJsonObject("tracks")
                .getAsJsonObject("mymusic:a").get("title").getAsString());
            assertEquals(input.toString(), Files.readString(titles));
        }
        Path catalog = directory.resolve("assets/better_cobblemon_music/catalogs/extensions/mymusic.json");
        String good = Files.readString(catalog);
        input.addProperty("mymusic:a", false);
        Files.writeString(titles, input.toString());
        assertNotEquals(0, update(script));
        assertEquals(good, Files.readString(catalog));
    }

    private int update(Path script) throws Exception {
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("script-output.txt").toFile()).start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) { process.destroyForcibly(); }
        assertTrue(completed, "PowerShell template must not hang");
        return process.exitValue();
    }
}
