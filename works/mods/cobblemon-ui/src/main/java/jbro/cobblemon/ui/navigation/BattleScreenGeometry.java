package jbro.cobblemon.ui.navigation;

import jbro.cobblemon.ui.extended.ui.shared.BattleTargetLayout;
import jbro.cobblemon.ui.extended.ui.shared.BattleSwitchLayout;
import jbro.cobblemon.ui.extended.MoveTooltipAnchor;
import java.util.List;
import java.util.stream.IntStream;

/** Shared visual and input bounds for the redesigned battle controls. */
public final class BattleScreenGeometry {
    public static final int MOVE_WIDTH = 140;
    public static final int MOVE_HEIGHT = 32;
    public static final int GIMMICK_WIDTH = 90;
    public static final int GIMMICK_HEIGHT = 26;
    /** An icon-only gimmick tile, square. */
    public static final int GIMMICK_COMPACT_WIDTH = GIMMICK_HEIGHT;
    public static final int GIMMICK_GAP = 4;
    public static final int GIMMICK_MOVE_GAP = 8;
    public static final int FOCUS_PROTRUSION = 7;
    public static final int SWITCH_WIDTH = 105;
    public static final int SWITCH_HEIGHT = 20;

    private BattleScreenGeometry() {
    }

    public static List<UiRect> moveTiles(int screenWidth, int screenHeight, int count) {
        return BattleMenuLayout.vertical(screenWidth, screenHeight, MOVE_WIDTH, MOVE_HEIGHT, 4, 0,
                moveBottomMargin(screenWidth), count);
    }

    /**
     * A free-standing column centred on the full four-move grid and kept left of it. The move tooltip
     * ([tooltipWidth] wide, 0 when tooltips are off) stands left of this column, so the tiles carry their names only
     * when the tooltip still fits beside them; otherwise they are icon-only squares.
     */
    public static List<UiRect> gimmickTiles(int screenWidth, int screenHeight, int count, int tooltipWidth) {
        if (count < 0) {
            throw new IllegalArgumentException("Gimmick count must be non-negative");
        }
        if (count == 0) {
            return List.of();
        }

        List<UiRect> moveGrid = moveTiles(screenWidth, screenHeight, 4);
        int moveLeft = moveGrid.getFirst().x();
        int availableWidth = Math.max(0, moveLeft - GIMMICK_MOVE_GAP - FOCUS_PROTRUSION);
        int fullLeft = moveLeft - GIMMICK_MOVE_GAP - GIMMICK_WIDTH;
        boolean named = tooltipWidth <= 0
                || fullLeft - MoveTooltipAnchor.VISUAL_CLEARANCE - tooltipWidth >= MoveTooltipAnchor.SCREEN_MARGIN;
        int width = Math.min(named ? GIMMICK_WIDTH : GIMMICK_COMPACT_WIDTH, availableWidth);
        int x = Math.max(FOCUS_PROTRUSION, moveLeft - GIMMICK_MOVE_GAP - width);

        int gridTop = moveGrid.getFirst().y();
        UiRect lastMove = moveGrid.getLast();
        int gridBottom = lastMove.y() + lastMove.height();
        int totalHeight = count * GIMMICK_HEIGHT + (count - 1) * GIMMICK_GAP;
        int centredY = (gridTop + gridBottom - totalHeight) / 2;
        int y = Math.max(0, Math.min(Math.max(0, screenHeight - totalHeight), centredY));

        return IntStream.range(0, count)
                .mapToObj(index -> new UiRect(x,
                        y + index * (GIMMICK_HEIGHT + GIMMICK_GAP), width, GIMMICK_HEIGHT))
                .toList();
    }

    /** The vanilla hotbar is 182 wide and 22 tall at the bottom center; moves clear it when they would overlap. */
    static int moveBottomMargin(int screenWidth) {
        boolean overlapsHotbar = screenWidth / 2 + 91 > screenWidth - MOVE_WIDTH - FOCUS_PROTRUSION;
        return overlapsHotbar ? 24 : 16;
    }

    public static UiRect switchPanel(int screenWidth, int screenHeight) {
        return fromKit(BattleSwitchLayout.calculate(screenWidth, screenHeight).getPanel());
    }

    public static List<UiRect> switchTiles(int screenWidth, int screenHeight, int count) {
        if (count < 0 || count > 6) {
            throw new IllegalArgumentException("Party slot count must be between 0 and 6");
        }
        return BattleSwitchLayout.calculate(screenWidth, screenHeight).getAllies().stream()
                .limit(count).map(BattleScreenGeometry::fromKit).toList();
    }

    /** Preview-only rows mirror the player's hitboxes without introducing opponent click targets. */
    public static List<UiRect> switchOpponentTiles(int screenWidth, int screenHeight, int count) {
        if (screenWidth < 400) return List.of();
        if (count < 0 || count > 6) {
            throw new IllegalArgumentException("Party preview count must be between 0 and 6");
        }
        return BattleSwitchLayout.calculate(screenWidth, screenHeight).getOpponents().stream()
                .limit(count).map(BattleScreenGeometry::fromKit).toList();
    }

    public static UiRect switchDetails(int screenWidth, int screenHeight) {
        return fromKit(BattleSwitchLayout.calculate(screenWidth, screenHeight).getDetailBody());
    }

    public static UiRect switchBack(int screenWidth, int screenHeight) {
        return fromKit(BattleSwitchLayout.calculate(screenWidth, screenHeight).getBack());
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
        return fromKit(BattleTargetLayout.calculate(screenWidth, screenHeight, slotsPerSide).getPanel());
    }

    public static UiRect targetTile(int screenWidth, int screenHeight, int slotsPerSide, int row, int column) {
        if (row < 0 || row > 1 || column < 0 || column >= slotsPerSide) {
            throw new IllegalArgumentException("Target tile must be inside the two-side battle grid");
        }
        BattleTargetLayout.Result layout = BattleTargetLayout.calculate(screenWidth, screenHeight, slotsPerSide);
        return fromKit((row == 0 ? layout.getAllies() : layout.getOpponents()).get(column));
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

    /** Visible target cards fill their field slots; an unused field slot is never a click target. */
    public static UiRect targetCard(UiRect slot) {
        return fromKit(BattleTargetLayout.card(new jbro.cobblemon.uikit.UiRect(
                slot.x(), slot.y(), slot.width(), slot.height())));
    }

    public static UiRect targetBack(int screenWidth, int screenHeight, int slotsPerSide) {
        return fromKit(BattleTargetLayout.calculate(screenWidth, screenHeight, slotsPerSide).getBack());
    }

    private static UiRect fromKit(jbro.cobblemon.uikit.UiRect rect) {
        return new UiRect(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
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
