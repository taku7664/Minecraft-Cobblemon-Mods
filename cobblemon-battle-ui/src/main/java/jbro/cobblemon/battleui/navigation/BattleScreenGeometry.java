package jbro.cobblemon.battleui.navigation;

import java.util.ArrayList;
import java.util.List;

/** Shared visual and input bounds for the redesigned battle controls. */
public final class BattleScreenGeometry {
    public static final int MOVE_WIDTH = 140;
    public static final int MOVE_HEIGHT = 32;
    public static final int SWITCH_WIDTH = 118;
    public static final int SWITCH_HEIGHT = 34;

    private BattleScreenGeometry() {
    }

    public static List<UiRect> moveTiles(int screenWidth, int screenHeight, int count) {
        return BattleMenuLayout.vertical(screenWidth, screenHeight, MOVE_WIDTH, MOVE_HEIGHT, 4, 12, 16, count);
    }

    public static UiRect switchPanel(int screenWidth, int screenHeight) {
        return new UiRect(Math.max(0, (screenWidth - 260) / 2),
                Math.max(82, (screenHeight - 150) / 2), 260, 150);
    }

    public static List<UiRect> switchTiles(int screenWidth, int screenHeight, int count) {
        if (count < 0 || count > 6) {
            throw new IllegalArgumentException("Party slot count must be between 0 and 6");
        }
        UiRect panel = switchPanel(screenWidth, screenHeight);
        List<UiRect> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new UiRect(panel.x() + 9 + index % 2 * 124,
                    panel.y() + 25 + index / 2 * 39, SWITCH_WIDTH, SWITCH_HEIGHT));
        }
        return List.copyOf(result);
    }

    public static UiRect forfeitPanel(int screenWidth, int screenHeight) {
        return new UiRect(Math.max(0, (screenWidth - 220) / 2),
                Math.max(0, (screenHeight - 88) / 2 + 8), 220, 88);
    }

    public static UiRect forfeitAccept(int screenWidth, int screenHeight) {
        UiRect panel = forfeitPanel(screenWidth, screenHeight);
        return new UiRect(panel.x() + 36, panel.y() + 50, 68, 24);
    }

    public static UiRect forfeitCancel(int screenWidth, int screenHeight) {
        UiRect panel = forfeitPanel(screenWidth, screenHeight);
        return new UiRect(panel.x() + 116, panel.y() + 50, 68, 24);
    }

    /** Non-interactive draft: one narrow column per side, below the HUD and above the hotbar. */
    public static UiRect targetPanel(int screenWidth, int screenHeight, int slotsPerSide) {
        if (slotsPerSide < 2 || slotsPerSide > 3) {
            throw new IllegalArgumentException("Target selection supports two or three slots per side");
        }
        int width = Math.min(184, Math.max(0, screenWidth - 20));
        int height = slotsPerSide == 3 ? 84 : 74;
        int minY = slotsPerSide == 3 ? 126 : 88;
        int y = Math.max(0, Math.min(Math.max(minY, screenHeight - height - 56), screenHeight - height - 8));
        return new UiRect(Math.max(0, (screenWidth - width) / 2), y, width, height);
    }

    public static UiRect targetTile(int screenWidth, int screenHeight, int slotsPerSide, int row, int column) {
        if (row < 0 || row > 1 || column < 0 || column >= slotsPerSide) {
            throw new IllegalArgumentException("Target tile must be inside the two-side battle grid");
        }
        UiRect panel = targetPanel(screenWidth, screenHeight, slotsPerSide);
        int width = (panel.width() - 18) / 2;
        int rowStep = slotsPerSide == 3 ? 20 : 24;
        int rowHeight = slotsPerSide == 3 ? 19 : 22;
        return new UiRect(panel.x() + 6 + row * (width + 6),
                panel.y() + 22 + column * rowStep, width, rowHeight);
    }

    public static UiRect targetBack(int screenWidth, int screenHeight, int slotsPerSide) {
        UiRect panel = targetPanel(screenWidth, screenHeight, slotsPerSide);
        return new UiRect(panel.x() + panel.width() - 36, panel.y() + 3, 30, 13);
    }

    /** Cancels Cobblemon 1.8.1's 4 px per-slot X stagger in single-actor compact HUDs. */
    public static int compactHudSlotIndent(float nativeY, int slotsPerActor, int actorsPerSide) {
        if (slotsPerActor < 2 || actorsPerSide != 1) {
            return 0;
        }
        int rank = Math.round((nativeY - 10f) / 30f);
        if (rank < 0 || rank >= slotsPerActor || Math.abs(nativeY - (10 + rank * 30)) > 0.5f) {
            return 0;
        }
        return (slotsPerActor - rank - 1) * 4;
    }

    /** Preserve the native first-row anchor while giving each 30 px HUD a 3 px gap. */
    public static int compactHudVerticalOffset(float nativeY, int slotsPerActor, int actorsPerSide) {
        if (slotsPerActor < 2 || actorsPerSide != 1) {
            return 0;
        }
        int rank = Math.round((nativeY - 10f) / 30f);
        if (rank < 0 || rank >= slotsPerActor || Math.abs(nativeY - (10 + rank * 30)) > 0.5f) {
            return 0;
        }
        return rank * 3;
    }
}
