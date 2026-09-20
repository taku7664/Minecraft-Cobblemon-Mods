package jbro.cobblemon.battleui.extended.navigation;

import java.lang.ref.WeakReference;
import java.util.List;

public final class KeyboardTileFocus {
    private static WeakReference<Object> focused = new WeakReference<>(null);
    private static boolean mouseHoverAllowed = true;

    private KeyboardTileFocus() {
    }

    public static void set(Object tile) {
        focused = new WeakReference<>(tile);
        mouseHoverAllowed = false;
    }

    public static boolean isFocused(Object tile) {
        return focused.get() == tile;
    }

    public static int focusedIndex(List<?> tiles) {
        Object focusedTile = focused.get();
        if (focusedTile == null) {
            return -1;
        }
        for (int index = 0; index < tiles.size(); index++) {
            if (tiles.get(index) == focusedTile) {
                return index;
            }
        }
        return -1;
    }

    public static void clear() {
        focused.clear();
    }

    public static void useKeyboard() {
        mouseHoverAllowed = false;
    }

    public static void mouseMoved() {
        focused.clear();
        mouseHoverAllowed = true;
    }

    public static boolean allowsMouseHover() {
        return mouseHoverAllowed;
    }
}
