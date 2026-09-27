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
}
