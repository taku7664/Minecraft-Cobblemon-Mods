package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MixinPackageIsolationTest {
    @Test
    void mixinOwnedPackageContainsOnlyActualMixins() throws Exception {
        List<String> ordinaryClasses = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.toString();
                if (!name.endsWith(".java") && !name.endsWith(".kt")) continue;

                String source = Files.readString(path);
                if (source.startsWith("package jbro.cobblemon.battleui.extended.mixin")
                        && !source.contains("@Mixin(")) {
                    ordinaryClasses.add(name);
                }
            }
        }
        assertTrue(ordinaryClasses.isEmpty(), "Ordinary classes in the mixin-owned package: " + ordinaryClasses);
    }
}
