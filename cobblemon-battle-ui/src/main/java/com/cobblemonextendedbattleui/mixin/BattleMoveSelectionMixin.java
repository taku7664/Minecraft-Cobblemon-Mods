package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import jbro.cobblemon.battleui.extended.MoveTooltipRenderer;
import jbro.cobblemon.battleui.extended.BattleInfoPanel;
import jbro.cobblemon.battleui.extended.PanelConfig;
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.battleui.navigation.BattleMenuLayout;
import jbro.cobblemon.battleui.navigation.UiRect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * Mixin to add move tooltips to Cobblemon's move selection screen.
 * Hooks into renderWidget to track move tile bounds and render tooltips on hover.
 */
@Mixin(value = BattleMoveSelection.class, remap = false)
public class BattleMoveSelectionMixin {
    private static final int BUTTON_GAP = 4;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 10;

    @Shadow
    public List<MoveTile> moveTiles;

    /**
     * Clear move tile tracking at the start of each render frame.
     * Note: remap = true overrides class-level remap = false for this Minecraft method.
     */
    @Inject(method = "renderWidget", at = @At("HEAD"), remap = true)
    private void onRenderWidgetHead(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            MoveTooltipRenderer.INSTANCE.suspendForModal();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        List<UiRect> bounds = BattleMenuLayout.vertical(
                client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight(),
                BattleMoveSelection.MOVE_WIDTH,
                BattleMoveSelection.MOVE_HEIGHT,
                BUTTON_GAP,
                RIGHT_MARGIN,
                BOTTOM_MARGIN,
                moveTiles.size()
        );
        for (int index = 0; index < moveTiles.size(); index++) {
            BattleMoveTileAccessor tile = (BattleMoveTileAccessor) moveTiles.get(index);
            UiRect bound = bounds.get(index);
            tile.cobblemonBattleUi$setX(bound.x());
            tile.cobblemonBattleUi$setY(bound.y());
        }

        if (PanelConfig.INSTANCE.getEnableMoveTooltipsEffective()) {
            MoveTooltipRenderer.INSTANCE.clear();
        }
    }

    /**
     * After move tiles are rendered, register their bounds and render tooltip.
     * Note: remap = true overrides class-level remap = false for this Minecraft method.
     */
    @Inject(method = "renderWidget", at = @At("RETURN"), remap = true)
    private void onRenderWidgetReturn(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            MoveTooltipRenderer.INSTANCE.suspendForModal();
            return;
        }

        if (!PanelConfig.INSTANCE.getEnableMoveTooltipsEffective()) {
            return;
        }

        // Identify which Pokemon is selecting a move (critical for doubles)
        SingleActionRequest request = ((BattleActionSelection) (Object) this).getRequest();
        UUID pokemonUuid = null;
        if (request != null && request.getActivePokemon() != null
                && request.getActivePokemon().getBattlePokemon() != null) {
            pokemonUuid = request.getActivePokemon().getBattlePokemon().getUuid();
        }
        MoveTooltipRenderer.INSTANCE.setActivePokemonUuid(pokemonUuid);

        // Register each move tile's bounds for hover detection
        for (MoveTile tile : moveTiles) {
            MoveTooltipRenderer.INSTANCE.registerMoveTile(
                tile.getX(),
                tile.getY(),
                BattleMoveSelection.MOVE_WIDTH,
                BattleMoveSelection.MOVE_HEIGHT,
                tile.getMoveTemplate(),
                tile.getMove().getPp(),
                tile.getMove().getMaxpp()
            );
        }

        // Mouse and keyboard share one tooltip target. A stationary mouse must
        // not overwrite keyboard focus after navigation input.
        if (KeyboardTileFocus.allowsMouseHover()) {
            MoveTooltipRenderer.INSTANCE.updateHoverState(mouseX, mouseY);
        } else {
            MoveTooltipRenderer.INSTANCE.updateKeyboardFocusState(
                    KeyboardTileFocus.focusedIndex(moveTiles)
            );
        }
        MoveTooltipRenderer.INSTANCE.renderTooltip(context);

        // Handle font scaling input ([ ] keys)
        MoveTooltipRenderer.INSTANCE.handleInput();
    }
}
