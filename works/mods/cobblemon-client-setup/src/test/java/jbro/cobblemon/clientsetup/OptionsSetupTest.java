package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OptionsSetupTest {
    private static final Set<String> IRIS = Set.of("iris");
    @TempDir Path gameDirectory;

    private Path config() { return gameDirectory.resolve("config"); }
    private Path options() { return gameDirectory.resolve("options.txt"); }
    private Path shaders() { return gameDirectory.resolve("optionsshaders.txt"); }
    private void file(String relative) throws Exception {
        Path path = gameDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
    }
    private void allPacks() throws Exception {
        for (String pack : List.of("spawn-notification-ment.zip", "CCC_2.21.zip", "cobblemon-korean-translation-bundle-1.3.0.zip",
            "better-cobblemon-music-resourcepack-1.3.18.zip", "galmuri11-8px.zip", "Whimscape x Cobblemon v1.8.zip",
            "Whimscape_1.20.2-1.21.11_r6.zip", "RCT Trainers+ [1.7] v2.2.zip", "MoreRadicalTextures1.8.zip", "E19-Xaero-Icons-1.5.1.zip"))
            file("resourcepacks/" + pack);
    }

    @Test void freshInstallGetsKoreanPacksInOrderAndShaderOn() throws Exception {
        allPacks();
        file("shaderpacks/LumaVale-0.1.3.zip");
        assertEquals(2, OptionsSetup.apply(gameDirectory, config(), IRIS));
        String text = Files.readString(options());
        assertTrue(text.contains("lang:ko_kr\n"));
        assertTrue(text.contains("skipMultiplayerWarning:true\n"));
        assertTrue(text.contains("resourcePacks:[\"vanilla\",\"fabric\",\"file/spawn-notification-ment.zip\",\"file/CCC_2.21.zip\","
            + "\"file/cobblemon-korean-translation-bundle-1.3.0.zip\",\"file/better-cobblemon-music-resourcepack-1.3.18.zip\","
            + "\"file/galmuri11-8px.zip\",\"file/Whimscape x Cobblemon v1.8.zip\",\"file/Whimscape_1.20.2-1.21.11_r6.zip\","
            + "\"file/RCT Trainers+ [1.7] v2.2.zip\",\"file/MoreRadicalTextures1.8.zip\",\"file/E19-Xaero-Icons-1.5.1.zip\"]\n"));
        String shader = Files.readString(shaders());
        assertTrue(shader.contains("shaderPack=LumaVale-0.1.3.zip"));
        assertTrue(shader.contains("enableShaders=true"));
    }

    @Test void existingOptionsKeepOtherSettingsAndPlayerPacksButDropStaleOnes() throws Exception {
        file("resourcepacks/better-cobblemon-music-resourcepack-1.3.18.zip");
        Files.writeString(options(), "version:3955\nrenderDistance:6\nlang:en_us\n"
            + "resourcePacks:[\"vanilla\",\"fabric\",\"cobblemon:uniqueshinyforms\",\"file/better-cobblemon-music-resourcepack-1.3.17.zip\",\"file/mine.zip\"]\n");
        OptionsSetup.apply(gameDirectory, config(), Set.of());
        String text = Files.readString(options());
        assertTrue(text.contains("renderDistance:6\n"));
        assertTrue(text.contains("lang:ko_kr\n"));
        assertTrue(text.contains("resourcePacks:[\"vanilla\",\"fabric\",\"cobblemon:uniqueshinyforms\",\"file/mine.zip\","
            + "\"file/better-cobblemon-music-resourcepack-1.3.18.zip\"]\n"));
    }

    @Test void appliedOnceSoPlayerChoicesStand() throws Exception {
        file("shaderpacks/LumaVale-0.1.3.zip");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        Files.writeString(options(), "lang:en_us\n");
        Files.writeString(shaders(), "shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("lang:en_us\n", Files.readString(options()));
        assertEquals("shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n", Files.readString(shaders()));
    }

    @Test void staleLumaValeNameMovesToInstalledVersionKeepingOnOff() throws Exception {
        file("shaderpacks/LumaVale-0.1.3.zip");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        Files.writeString(shaders(), "shaderPack=LumaVale-0.1.0.zip\nenableShaders=false\n");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n", Files.readString(shaders()));
        Files.writeString(shaders(), "shaderPack=Complementary.zip\nenableShaders=true\n");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        assertEquals("shaderPack=Complementary.zip\nenableShaders=true\n", Files.readString(shaders()));
    }

    @Test void withoutIrisOrLumaValeTheShaderRuleWaits() throws Exception {
        assertEquals(1, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertFalse(Files.exists(shaders()));
        assertNull(SetupFiles.readState(config()).getProperty(OptionsSetup.SHADER_RULE));
        file("shaderpacks/LumaVale-0.1.3.zip");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), Set.of()));
        assertEquals(1, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertTrue(Files.readString(shaders()).contains("enableShaders=true"));
    }

    @Test void newestLumaValeByVersionNumber() throws Exception {
        file("shaderpacks/LumaVale-0.1.9.zip");
        file("shaderpacks/LumaVale-0.1.10.zip");
        assertEquals("LumaVale-0.1.10.zip", OptionsSetup.installedLumaVale(gameDirectory).orElseThrow());
    }
}
