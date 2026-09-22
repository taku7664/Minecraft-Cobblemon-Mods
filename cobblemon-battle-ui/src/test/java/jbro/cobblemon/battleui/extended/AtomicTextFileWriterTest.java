package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicTextFileWriterTest {
    @TempDir
    Path tempDirectory;

    @Test
    void replacesExistingContentWithoutLeavingTemporaryFiles() throws Exception {
        Path target = tempDirectory.resolve("nested/config.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");

        AtomicTextFileWriter.write(target, "new");

        assertEquals("new", Files.readString(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void createsTheParentDirectoryForAFirstSave() throws Exception {
        Path target = tempDirectory.resolve("new/config.json");

        AtomicTextFileWriter.write(target, "{}\n");

        assertTrue(Files.isRegularFile(target));
        assertEquals("{}\n", Files.readString(target));
    }
}
