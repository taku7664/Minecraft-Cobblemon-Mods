package jbro.cobblemon.battleui.navigation;

import java.util.ArrayList;
import java.util.List;

/** Shared visual and input bounds for the redesigned battle controls. */
public final class BattleScreenGeometry {
    public static final int MOVE_WIDTH = 140;
    public static final int MOVE_HEIGHT = 32;
    public static final int FOCUS_PROTRUSION = 5;
    public static final int SWITCH_WIDTH = 120;
    public static final int SWITCH_HEIGHT = 20;

    private BattleScreenGeometry() {
    }

    public static List<UiRect> moveTiles(int screenWidth, int screenHeight, int count) {
        return BattleMenuLayout.vertical(screenWidth, screenHeight, MOVE_WIDTH, MOVE_HEIGHT, 4, 0, 16, count);
    }

    public static UiRect switchPanel(int screenWidth, int screenHeight) {
        return new UiRect(Math.max(0, (screenWidth - 400) / 2),
                Math.max(82, (screenHeight - 150) / 2), 274, 150);
    }

    public static List<UiRect> switchTiles(int screenWidth, int screenHeight, int count) {
        if (count < 0 || count > 6) {
            throw new IllegalArgumentException("Party slot count must be between 0 and 6");
        }
        UiRect panel = switchPanel(screenWidth, screenHeight);
        List<UiRect> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new UiRect(panel.x(),
                    panel.y() + 17 + index * 22, SWITCH_WIDTH, SWITCH_HEIGHT));
        }
        return List.copyOf(result);
    }

    /** Preview-only rows mirror the player's hitboxes without introducing opponent click targets. */
    public static List<UiRect> switchOpponentTiles(int screenWidth, int screenHeight, int count) {
        if (screenWidth < 400) return List.of();
        if (count < 0 || count > 6) {
            throw new IllegalArgumentException("Party preview count must be between 0 and 6");
        }
        UiRect panel = switchPanel(screenWidth, screenHeight);
        List<UiRect> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new UiRect(screenWidth - panel.x() - SWITCH_WIDTH,
                    panel.y() + 17 + index * 22, SWITCH_WIDTH, SWITCH_HEIGHT));
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

    /** Shared draft/live modal: one horizontal field row per side, plus Back inside one shell. */
    public static UiRect targetPanel(int screenWidth, int screenHeight, int slotsPerSide) {
        if (slotsPerSide < 2 || slotsPerSide > 3) {
            throw new IllegalArgumentException("Target selection supports two or three slots per side");
        }
        int width = Math.min(slotsPerSide == 3 ? 246 : 180, Math.max(0, screenWidth - 20));
        int height = 96;
        int y = Math.max(0, Math.min(Math.max(70, screenHeight - height - 30), screenHeight - height - 8));
        return new UiRect(Math.max(0, (screenWidth - width) / 2), y, width, height);
    }

    public static UiRect targetTile(int screenWidth, int screenHeight, int slotsPerSide, int row, int column) {
        if (row < 0 || row > 1 || column < 0 || column >= slotsPerSide) {
            throw new IllegalArgumentException("Target tile must be inside the two-side battle grid");
        }
        UiRect panel = targetPanel(screenWidth, screenHeight, slotsPerSide);
        int gap = slotsPerSide == 3 ? 8 : 10;
        int width = Math.max(0, (panel.width() - 20 - (slotsPerSide - 1) * gap) / slotsPerSide);
        int rowHeight = slotsPerSide == 3 ? 18 : 20;
        return new UiRect(panel.x() + 10 + column * (width + gap),
                panel.y() + (row == 0 ? 71 : 36), width, rowHeight);
    }

    /** Mirrors Cobblemon's opponent field order without relying on list index as visual row. */
    public static UiRect targetTileForIndex(int screenWidth, int screenHeight, int slotsPerSide,
                                            int index, boolean ally) {
        if (index < 0 || index >= slotsPerSide * 2) {
            throw new IllegalArgumentException("Target index must be inside the active battle field");
        }
        int fieldPosition = index % slotsPerSide;
        return targetTile(screenWidth, screenHeight, slotsPerSide, ally ? 0 : 1,
                ally ? fieldPosition : slotsPerSide - 1 - fieldPosition);
    }

    /** Equal compact buttons for both sides; the unused field slot is never a click target. */
    public static UiRect targetCard(UiRect slot) {
        int width = Math.min(slot.width(), 70);
        return new UiRect(slot.x() + (slot.width() - width) / 2, slot.y(), width, slot.height());
    }

    public static UiRect targetBack(int screenWidth, int screenHeight, int slotsPerSide) {
        UiRect panel = targetPanel(screenWidth, screenHeight, slotsPerSide);
        return new UiRect(panel.x() + panel.width() - 50, panel.y() + 6, 40, 16);
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

    /** The 25 px edge cards use a 28 px row step instead of Cobblemon's native 30 px. */
    public static int compactHudRowCompression(float nativeY, int slotsPerActor, int actorsPerSide) {
        if (slotsPerActor < 2 || actorsPerSide != 1) {
            return 0;
        }
        int rank = Math.round((nativeY - 10f) / 30f);
        if (rank < 0 || rank >= slotsPerActor || Math.abs(nativeY - (10 + rank * 30)) > 0.5f) {
            return 0;
        }
        return rank * 2;
    }
}
