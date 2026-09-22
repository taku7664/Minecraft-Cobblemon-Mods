package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import java.util.List;
import jbro.cobblemon.battleui.navigation.BattleMenuLayout;
import jbro.cobblemon.battleui.navigation.UiRect;

/** Applies the shared bottom-right layout before rendering or hit testing commands. */
public final class BattleCommandLayout {
    private static final int BUTTON_GAP = 4;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 10;

    private BattleCommandLayout() {}

    public static void place(List<BattleOptionTile> tiles, int screenWidth, int screenHeight) {
        List<UiRect> bounds = BattleMenuLayout.vertical(
                screenWidth,
                screenHeight,
                BattleOptionTile.OPTION_WIDTH,
                BattleOptionTile.OPTION_HEIGHT,
                BUTTON_GAP,
                RIGHT_MARGIN,
                BOTTOM_MARGIN,
                tiles.size()
        );
        for (int index = 0; index < tiles.size(); index++) {
            BattleOptionTileAccessor tile = (BattleOptionTileAccessor) (Object) tiles.get(index);
            UiRect bound = bounds.get(index);
            tile.cobblemonBattleUi$setX(bound.x());
            tile.cobblemonBattleUi$setY(bound.y());
        }
    }
}
