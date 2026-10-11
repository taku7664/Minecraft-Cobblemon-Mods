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
    private Path iris() { return config().resolve("iris.properties"); }
    private void file(String relative) throws Exception {
        Path path = gameDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
    }
    private void iris(String content) throws Exception {
        Files.createDirectories(config());
        Files.writeString(iris(), content);
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
        String shader = Files.readString(iris());
        assertTrue(shader.contains("shaderPack=LumaVale-0.1.3.zip"));
        assertTrue(shader.contains("enableShaders=true"));
        assertFalse(Files.exists(gameDirectory.resolve("optionsshaders.txt")));
        assertEquals("true", SetupFiles.readState(config()).getProperty(OptionsSetup.FRESH_INSTALL));
    }

    @Test void updatedProfileKeepsPlayerSettingsAndOnlyFollowsRenamedFiles() throws Exception {
        file("resourcepacks/better-cobblemon-music-resourcepack-1.3.18.zip");
        file("resourcepacks/CCC_2.21.zip");
        file("shaderpacks/LumaVale-0.1.3.zip");
        String options = "version:3955\nrenderDistance:6\nlang:en_us\n"
            + "resourcePacks:[\"vanilla\",\"fabric\",\"file/better-cobblemon-music-resourcepack-1.3.17.zip\",\"file/mine.zip\"]\n";
        Files.writeString(options(), options);
        iris("enableShaders=false\nshaderPack=LumaVale-0.1.0.zip\n");
        assertEquals(2, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("false", SetupFiles.readState(config()).getProperty(OptionsSetup.FRESH_INSTALL));
        assertEquals("true", SetupFiles.readState(config()).getProperty(OptionsSetup.OPTIONS_RULE));
        // Language, the disabled CCC pack and pack order stay; the removed music zip follows to its new version in place.
        assertEquals("version:3955\nrenderDistance:6\nlang:en_us\n"
            + "resourcePacks:[\"vanilla\",\"fabric\",\"file/better-cobblemon-music-resourcepack-1.3.18.zip\",\"file/mine.zip\"]\n",
            Files.readString(options()));
        assertEquals("enableShaders=false\nshaderPack=LumaVale-0.1.3.zip\n", Files.readString(iris()));
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), IRIS));
    }

    @Test void profileThatAlreadyRanTheOldRuleIsTreatedAsAnUpdate() throws Exception {
        Files.createDirectories(config().resolve("cobblemon-client-setup"));
        Files.writeString(config().resolve("cobblemon-client-setup/applied-defaults.properties"),
            "options-defaults-v1=true\nshader-defaults-v1=true\n");
        Files.writeString(options(), "lang:en_us\n");
        iris("enableShaders=false\n");
        file("shaderpacks/LumaVale-0.1.3.zip");
        assertEquals(1, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("lang:en_us\n", Files.readString(options()));
        assertEquals("enableShaders=false\n", Files.readString(iris()));
        assertEquals("true", SetupFiles.readState(config()).getProperty(OptionsSetup.SHADER_RULE));
    }

    @Test void appliedOnceSoPlayerChoicesStand() throws Exception {
        file("shaderpacks/LumaVale-0.1.3.zip");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        Files.writeString(options(), "lang:en_us\n");
        iris("shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("lang:en_us\n", Files.readString(options()));
        assertEquals("shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n", Files.readString(iris()));
    }

    @Test void staleLumaValeNameMovesToInstalledVersionKeepingOnOff() throws Exception {
        file("shaderpacks/LumaVale-0.1.3.zip");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        iris("shaderPack=LumaVale-0.1.0.zip\nenableShaders=false\n");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertEquals("shaderPack=LumaVale-0.1.3.zip\nenableShaders=false\n", Files.readString(iris()));
        iris("shaderPack=Complementary.zip\nenableShaders=true\n");
        OptionsSetup.apply(gameDirectory, config(), IRIS);
        assertEquals("shaderPack=Complementary.zip\nenableShaders=true\n", Files.readString(iris()));
    }

    @Test void freshInstallStillGetsTheShaderWhenIrisArrivesLater() throws Exception {
        assertEquals(1, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertFalse(Files.exists(iris()));
        assertNull(SetupFiles.readState(config()).getProperty(OptionsSetup.SHADER_RULE));
        file("shaderpacks/LumaVale-0.1.3.zip");
        assertEquals(0, OptionsSetup.apply(gameDirectory, config(), Set.of()));
        assertEquals(1, OptionsSetup.apply(gameDirectory, config(), IRIS));
        assertTrue(Files.readString(iris()).contains("enableShaders=true"));
    }

    @Test void folderPackAndPresentFilesAreLeftAlone() throws Exception {
        Files.createDirectories(gameDirectory.resolve("resourcepacks/better-cobblemon-music-resourcepack-1.3.18"));
        file("resourcepacks/better-cobblemon-music-resourcepack-1.3.19.zip");
        file("resourcepacks/CCC_2.21.zip");
        String options = "resourcePacks:[\"vanilla\",\"file/better-cobblemon-music-resourcepack-1.3.18\",\"file/CCC_2.21.zip\"]\n";
        Files.writeString(options(), options);
        OptionsSetup.apply(gameDirectory, config(), Set.of());
        OptionsSetup.apply(gameDirectory, config(), Set.of());
        assertEquals(options, Files.readString(options()));
    }

    @Test void missingPackWithoutReplacementIsDroppedAndDuplicatesAvoided() throws Exception {
        file("resourcepacks/CCC_2.21.zip");
        Files.writeString(options(), "resourcePacks:[\"file/CCC_2.9.zip\",\"file/CCC_2.21.zip\",\"file/galmuri11-8px.zip\"]\n");
        OptionsSetup.apply(gameDirectory, config(), Set.of());
        OptionsSetup.apply(gameDirectory, config(), Set.of());
        assertEquals("resourcePacks:[\"file/CCC_2.21.zip\"]\n", Files.readString(options()));
    }

    @Test void newestByVersionNumber() throws Exception {
        file("shaderpacks/LumaVale-0.1.9.zip");
        file("shaderpacks/LumaVale-0.1.10.zip");
        assertEquals("LumaVale-0.1.10.zip", OptionsSetup.installedLumaVale(gameDirectory).orElseThrow());
        file("resourcepacks/CCC_2.9.zip");
        file("resourcepacks/CCC_2.21.zip");
        assertEquals(List.of("CCC_2.21.zip"), OptionsSetup.installedPacks(gameDirectory));
    }
}
