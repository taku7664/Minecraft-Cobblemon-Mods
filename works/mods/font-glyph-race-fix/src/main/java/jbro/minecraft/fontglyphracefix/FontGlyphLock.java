package jbro.minecraft.fontglyphracefix;

import java.util.function.Supplier;

/** One monitor shared by TrueType glyph measurement and bitmap upload. */
public final class FontGlyphLock {
    private static final Object MONITOR = new Object();

    private FontGlyphLock() {
    }

    public static <T> T call(Supplier<T> operation) {
        synchronized (MONITOR) {
            return operation.get();
        }
    }

    public static void run(Runnable operation) {
        synchronized (MONITOR) {
            operation.run();
        }
    }
}
