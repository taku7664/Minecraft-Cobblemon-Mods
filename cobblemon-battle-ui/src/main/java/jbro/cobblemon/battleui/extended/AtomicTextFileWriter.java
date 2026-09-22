package jbro.cobblemon.battleui.extended;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes complete UTF-8 text before replacing the destination file. */
public final class AtomicTextFileWriter {
    private AtomicTextFileWriter() {}

    public static void write(Path target, String contents) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + target);
        }

        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absoluteTarget.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8, WRITE, TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, WRITE)) {
                channel.force(true);
            }

            try {
                Files.move(temporary, absoluteTarget, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException exception) {
                Files.move(temporary, absoluteTarget, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
