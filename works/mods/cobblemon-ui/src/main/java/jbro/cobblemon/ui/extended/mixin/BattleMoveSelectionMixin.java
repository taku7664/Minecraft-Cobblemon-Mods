package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGimmickButton;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import jbro.cobblemon.ui.extended.MoveTooltipRenderer;
import jbro.cobblemon.ui.extended.BattleInfoPanel;
import jbro.cobblemon.ui.extended.PanelConfig;
import jbro.cobblemon.ui.extended.UIUtils;
import jbro.cobblemon.ui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import jbro.cobblemon.ui.navigation.UiRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/**
 * Mixin to add move tooltips to Cobblemon's move selection screen.
 * Hooks into renderWidget to track move tile bounds and render tooltips on hover.
 */
@Mixin(value = BattleMoveSelection.class, remap = false)
public class BattleMoveSelectionMixin {
    @Shadow
    public List<MoveTile> moveTiles;

    /**
     * Clear move tile tracking at the start of each render frame.
     * Note: remap = true overrides class-level remap = false for this Minecraft method.
     */
    @Inject(method = "renderWidget", at = @At("HEAD"), remap = true)
    private void onRenderWidgetHead(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            MoveTooltipRenderer.INSTANCE.suspendForModal();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        List<UiRect> bounds = BattleScreenGeometry.moveTiles(
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(),
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
    private void onRenderWidgetReturn(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            MoveTooltipRenderer.INSTANCE.suspendForModal();
            return;
        }

        BattleMoveSelection selection = (BattleMoveSelection) (Object) this;
        List<BattleGimmickButton> gimmicks = selection.getGimmickButtons();
        List<UiRect> gimmickBounds = BattleScreenGeometry.gimmickTiles(
                Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                Minecraft.getInstance().getWindow().getGuiScaledHeight(), gimmicks.size(), MoveTooltipRenderer.reservedWidth());
        if (!gimmickBounds.isEmpty()) {
            MoveTooltipRenderer.INSTANCE.registerGimmickColumn(gimmickBounds.getFirst().x());
        }
        if (PanelConfig.INSTANCE.getEnableMoveTooltipsEffective()) {
            cobblemonBattleUi$moveTooltip(context, mouseX, mouseY);
        }

        // Over the tooltip: a tooltip wider than the room left of the column (large tooltip text) must not hide
        // which gimmick is armed while a move is chosen.
        context.pose().pushPose();
        context.pose().translate(0.0, 0.0, UIUtils.POPUP_Z_OFFSET + 10.0);
        for (int index = 0; index < gimmicks.size(); index++) {
            BattleGimmickButton button = gimmicks.get(index);
            UiRect bound = gimmickBounds.get(index);
            boolean emphasized = KeyboardTileFocus.allowsMouseHover()
                    ? bound.contains(mouseX, mouseY)
                    : KeyboardTileFocus.isFocused(button);
            BattleControlRenderer.gimmick(context, selection, button,
                    ((BattleGimmickButtonAccessor) button).cobblemonBattleUi$getTextureId(),
                    bound, emphasized, selection.getOpacity());
        }
        context.pose().popPose();
    }

    @Unique
    private void cobblemonBattleUi$moveTooltip(GuiGraphics context, int mouseX, int mouseY) {
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
                tile.getX() - BattleScreenGeometry.FOCUS_PROTRUSION,
                tile.getY(),
                BattleScreenGeometry.MOVE_WIDTH + BattleScreenGeometry.FOCUS_PROTRUSION,
                BattleScreenGeometry.MOVE_HEIGHT,
                tile.getMoveTemplate(),
                tile.getMove().getPp(),
                tile.getMove().getMaxpp(),
                Moves.getByName(tile.getMove().getId())
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

    @Unique
    private boolean cobblemonBattleUi$forwardingGimmickClick;

    /**
     * A click on the gimmick column is passed on as a click on the middle of that gimmick's own (no longer drawn)
     * button, so whichever click handling is in place toggles it: Cobblemon's, or Mega Showdown's, which overwrites
     * this method and tests the buttons inside its own lambdas, where a redirect cannot reach. A click on the old
     * buttons' spot does nothing now that nothing is drawn there.
     */
    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$gimmickColumnClick(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (cobblemonBattleUi$forwardingGimmickClick) {
            return;
        }
        BattleMoveSelection selection = (BattleMoveSelection) (Object) this;
        List<BattleGimmickButton> gimmicks = selection.getGimmickButtons();
        if (gimmicks.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        List<UiRect> bounds = BattleScreenGeometry.gimmickTiles(
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(), gimmicks.size(), MoveTooltipRenderer.reservedWidth());
        for (int index = 0; index < gimmicks.size(); index++) {
            if (!bounds.get(index).contains(mouseX, mouseY)) {
                continue;
            }
            BattleGimmickButton button = gimmicks.get(index);
            cobblemonBattleUi$forwardingGimmickClick = true;
            try {
                // Its hit area is WIDTH x HEIGHT at SCALE from (x, y).
                cir.setReturnValue(selection.mousePrimaryClicked(
                        button.getX() + BattleGimmickButton.WIDTH * BattleGimmickButton.SCALE / 2.0,
                        button.getY() + BattleGimmickButton.HEIGHT * BattleGimmickButton.SCALE / 2.0));
            } finally {
                cobblemonBattleUi$forwardingGimmickClick = false;
            }
            return;
        }
        for (BattleGimmickButton button : gimmicks) {
            if (button.isHovered(mouseX, mouseY)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
