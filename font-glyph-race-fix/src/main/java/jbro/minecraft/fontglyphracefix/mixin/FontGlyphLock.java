package jbro.minecraft.fontglyphracefix.mixin;

import java.util.function.Supplier;

/** One monitor shared by TrueType glyph measurement and bitmap upload. */
final class FontGlyphLock {
    private static final Object MONITOR = new Object();

    private FontGlyphLock() {
    }

    static <T> T call(Supplier<T> operation) {
        synchronized (MONITOR) {
            return operation.get();
        }
    }

    static void run(Runnable operation) {
        synchronized (MONITOR) {
            operation.run();
        }
    }
}
