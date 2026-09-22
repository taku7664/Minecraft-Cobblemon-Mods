package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUIClient;
import jbro.cobblemon.battleui.extended.BattleInfoPanel;
import jbro.cobblemon.battleui.extended.navigation.BattleGuiNavigationAccess;
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.battleui.navigation.ActionSubmissionGate;
import jbro.cobblemon.battleui.navigation.BattleMenuNavigator;
import jbro.cobblemon.battleui.navigation.FocusOwnership;
import jbro.cobblemon.battleui.navigation.GridMenuNavigator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

@Mixin(value = BattleGUI.class, remap = false)
public abstract class BattleGuiNavigationMixin implements BattleGuiNavigationAccess {
    @Unique
    private final FocusOwnership cobblemonBattleUi$focusOwnership = new FocusOwnership();

    @Unique
    private final ActionSubmissionGate cobblemonBattleUi$submissionGate = new ActionSubmissionGate();

    @Unique
    private BattleActionSelection cobblemonBattleUi$gateSelection;

    @Unique
    private BattleActionSelection cobblemonBattleUi$gridSelection;

    @Unique
    private int cobblemonBattleUi$gridIndex = -1;

    @Unique
    private boolean cobblemonBattleUi$mousePositionKnown;

    @Unique
    private int cobblemonBattleUi$lastMouseX;

    @Unique
    private int cobblemonBattleUi$lastMouseY;

    @Shadow
    public abstract BattleActionSelection getCurrentActionSelection();

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = true)
    private void cobblemonBattleUi$blockCommandsBehindInformationOverlay(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), remap = true)
    private void cobblemonBattleUi$observeMouseMovement(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            return;
        }

        boolean mouseMoved = !cobblemonBattleUi$mousePositionKnown
                || mouseX != cobblemonBattleUi$lastMouseX
                || mouseY != cobblemonBattleUi$lastMouseY;
        cobblemonBattleUi$mousePositionKnown = true;
        cobblemonBattleUi$lastMouseX = mouseX;
        cobblemonBattleUi$lastMouseY = mouseY;
        if (mouseMoved) {
            KeyboardTileFocus.mouseMoved();
        }

        BattleGeneralActionSelection selection = cobblemonBattleUi$getGeneralSelection();
        if (selection == null) {
            return;
        }

        List<BattleOptionTile> tiles = selection.getTiles();
        MinecraftClient client = MinecraftClient.getInstance();
        BattleCommandLayout.place(
                tiles,
                client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight()
        );
        int hoveredIndex = -1;
        for (int index = 0; index < tiles.size(); index++) {
            if (tiles.get(index).isHovered(mouseX, mouseY)) {
                hoveredIndex = index;
                break;
            }
        }

        int previousIndex = cobblemonBattleUi$focusOwnership.selectedIndex();
        cobblemonBattleUi$focusOwnership.mouseMoved(mouseX, mouseY, hoveredIndex);
        if (previousIndex != cobblemonBattleUi$focusOwnership.selectedIndex()) {
            cobblemonBattleUi$applyFocus(tiles, cobblemonBattleUi$focusOwnership.selectedIndex());
        }
    }

    @Inject(method = "render", at = @At("RETURN"), remap = true)
    private void cobblemonBattleUi$renderInformationOverlayLast(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        BattleInfoPanel.INSTANCE.renderForeground(context);
    }

    @Override
    public boolean cobblemonBattleUi$handleNavigationKey(
            int keyCode,
            int scanCode,
            int modifiers
    ) {
        BattleActionSelection currentSelection = getCurrentActionSelection();
        if (currentSelection == null) {
            return false;
        }

        boolean navigationKey = switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W,
                    GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S,
                    GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A,
                    GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> true;
            default -> CobblemonExtendedBattleUIClient.INSTANCE.getSelectActionKey().matchesKey(keyCode, scanCode);
        };
        if (navigationKey) {
            KeyboardTileFocus.useKeyboard();
        }

        if (currentSelection instanceof BattleSwitchPokemonSelection switchSelection
                && cobblemonBattleUi$handleSwitchKeys(switchSelection, keyCode, scanCode)) {
            return true;
        }
        if (currentSelection instanceof BattleMoveSelection moveSelection
                && cobblemonBattleUi$handleMoveKeys(moveSelection, keyCode, scanCode)) {
            return true;
        }
        if (currentSelection instanceof BattleTargetSelection targetSelection
                && cobblemonBattleUi$handleTargetKeys(targetSelection, keyCode, scanCode)) {
            return true;
        }

        BattleGeneralActionSelection selection = cobblemonBattleUi$getGeneralSelection();
        if (selection == null || selection.getTiles().isEmpty()) {
            return false;
        }

        List<BattleOptionTile> tiles = selection.getTiles();
        int direction = switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> -1;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 1;
            default -> 0;
        };

        if (direction != 0) {
            int focusedIndex = cobblemonBattleUi$focusedIndex(tiles);
            BattleMenuNavigator navigator = new BattleMenuNavigator(
                    Collections.nCopies(tiles.size(), true),
                    focusedIndex
            );
            int nextIndex = navigator.move(direction);
            cobblemonBattleUi$focusOwnership.keyboardSelected(nextIndex);
            cobblemonBattleUi$applyFocus(tiles, nextIndex);
            return true;
        }

        if (!CobblemonExtendedBattleUIClient.INSTANCE.getSelectActionKey().matchesKey(keyCode, scanCode)) {
            return false;
        }

        int focusedIndex = cobblemonBattleUi$focusedIndex(tiles);
        if (focusedIndex < 0) {
            focusedIndex = 0;
            cobblemonBattleUi$focusOwnership.keyboardSelected(focusedIndex);
            cobblemonBattleUi$applyFocus(tiles, focusedIndex);
        }

        if (cobblemonBattleUi$gateSelection != selection) {
            cobblemonBattleUi$gateSelection = selection;
            cobblemonBattleUi$submissionGate.unlock();
        }
        if (cobblemonBattleUi$submissionGate.tryLock()) {
            tiles.get(focusedIndex).getOnClick().invoke();
        }
        return true;
    }

    @Unique
    private BattleGeneralActionSelection cobblemonBattleUi$getGeneralSelection() {
        BattleActionSelection selection = getCurrentActionSelection();
        return selection instanceof BattleGeneralActionSelection general ? general : null;
    }

    @Unique
    private int cobblemonBattleUi$focusedIndex(List<BattleOptionTile> tiles) {
        for (int index = 0; index < tiles.size(); index++) {
            if (tiles.get(index).isFocused()) {
                return index;
            }
        }
        return -1;
    }

    @Unique
    private void cobblemonBattleUi$applyFocus(List<BattleOptionTile> tiles, int focusedIndex) {
        for (int index = 0; index < tiles.size(); index++) {
            tiles.get(index).setFocused(index == focusedIndex);
        }
    }

    @Unique
    private boolean cobblemonBattleUi$handleSwitchKeys(
            BattleSwitchPokemonSelection selection,
            int keyCode,
            int scanCode
    ) {
        List<BattleSwitchPokemonSelection.SwitchTile> tiles = selection.getTiles();
        return cobblemonBattleUi$handleGridKeys(
                selection,
                tiles,
                tile -> !tile.isFainted() && !tile.isCurrentlyInBattle(),
                keyCode,
                scanCode,
                tile -> selection.mousePrimaryClicked(tile.getX() + 1.0, tile.getY() + 1.0)
        );
    }

    @Unique
    private boolean cobblemonBattleUi$handleMoveKeys(
            BattleMoveSelection selection,
            int keyCode,
            int scanCode
    ) {
        List<? extends BattleMoveSelection.MoveTile> tiles = selection.getMoveTiles();
        return cobblemonBattleUi$handleVerticalKeys(
                selection,
                tiles,
                BattleMoveSelection.MoveTile::getSelectable,
                keyCode,
                scanCode,
                BattleMoveSelection.MoveTile::onClick
        );
    }

    @Unique
    private <T> boolean cobblemonBattleUi$handleVerticalKeys(
            BattleActionSelection selection,
            List<? extends T> tiles,
            Predicate<T> enabled,
            int keyCode,
            int scanCode,
            java.util.function.Consumer<T> onSelect
    ) {
        if (tiles.isEmpty()) {
            return false;
        }
        if (cobblemonBattleUi$gridSelection != selection) {
            cobblemonBattleUi$gridSelection = selection;
            cobblemonBattleUi$gridIndex = -1;
            KeyboardTileFocus.clear();
        }

        int direction = switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> -1;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 1;
            default -> 0;
        };
        if (direction != 0) {
            List<Boolean> enabledTiles = tiles.stream().map(enabled::test).toList();
            BattleMenuNavigator navigator = new BattleMenuNavigator(enabledTiles, cobblemonBattleUi$gridIndex);
            cobblemonBattleUi$gridIndex = navigator.move(direction);
            if (cobblemonBattleUi$gridIndex >= 0) {
                KeyboardTileFocus.set(tiles.get(cobblemonBattleUi$gridIndex));
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A
                || keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
            return true;
        }
        if (!CobblemonExtendedBattleUIClient.INSTANCE.getSelectActionKey().matchesKey(keyCode, scanCode)) {
            return false;
        }
        if (cobblemonBattleUi$gridIndex < 0 || !enabled.test(tiles.get(cobblemonBattleUi$gridIndex))) {
            List<Boolean> enabledTiles = tiles.stream().map(enabled::test).toList();
            cobblemonBattleUi$gridIndex = new BattleMenuNavigator(enabledTiles, -1).move(1);
        }
        if (cobblemonBattleUi$gridIndex >= 0 && cobblemonBattleUi$trySubmit(selection)) {
            onSelect.accept(tiles.get(cobblemonBattleUi$gridIndex));
        }
        return true;
    }

    @Unique
    private boolean cobblemonBattleUi$handleTargetKeys(
            BattleTargetSelection selection,
            int keyCode,
            int scanCode
    ) {
        List<? extends BattleTargetSelection.TargetTile> tiles = selection.getTargetTiles();
        return cobblemonBattleUi$handleGridKeys(
                selection,
                tiles,
                BattleTargetSelection.TargetTile::getSelectable,
                keyCode,
                scanCode,
                BattleTargetSelection.TargetTile::onClick
        );
    }

    @Unique
    private <T> boolean cobblemonBattleUi$handleGridKeys(
            BattleActionSelection selection,
            List<? extends T> tiles,
            Predicate<T> enabled,
            int keyCode,
            int scanCode,
            java.util.function.Consumer<T> onSelect
    ) {
        if (tiles.isEmpty()) {
            return false;
        }
        if (cobblemonBattleUi$gridSelection != selection) {
            cobblemonBattleUi$gridSelection = selection;
            cobblemonBattleUi$gridIndex = -1;
            KeyboardTileFocus.clear();
        }

        int horizontal = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> -1;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> 1;
            default -> 0;
        };
        int vertical = switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> -1;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 1;
            default -> 0;
        };

        if (horizontal != 0 || vertical != 0) {
            List<Boolean> enabledTiles = tiles.stream().map(enabled::test).toList();
            GridMenuNavigator navigator = new GridMenuNavigator(enabledTiles, 2, cobblemonBattleUi$gridIndex);
            cobblemonBattleUi$gridIndex = navigator.move(horizontal, vertical);
            if (cobblemonBattleUi$gridIndex >= 0) {
                KeyboardTileFocus.set(tiles.get(cobblemonBattleUi$gridIndex));
            }
            return true;
        }

        if (!CobblemonExtendedBattleUIClient.INSTANCE.getSelectActionKey().matchesKey(keyCode, scanCode)) {
            return false;
        }
        if (cobblemonBattleUi$gridIndex < 0 || !enabled.test(tiles.get(cobblemonBattleUi$gridIndex))) {
            List<Boolean> enabledTiles = tiles.stream().map(enabled::test).toList();
            cobblemonBattleUi$gridIndex = new GridMenuNavigator(enabledTiles, 2, -1).move(0, 1);
        }
        if (cobblemonBattleUi$gridIndex >= 0 && cobblemonBattleUi$trySubmit(selection)) {
            onSelect.accept(tiles.get(cobblemonBattleUi$gridIndex));
        }
        return true;
    }

    @Unique
    private boolean cobblemonBattleUi$trySubmit(BattleActionSelection selection) {
        if (cobblemonBattleUi$gateSelection != selection) {
            cobblemonBattleUi$gateSelection = selection;
            cobblemonBattleUi$submissionGate.unlock();
        }
        return cobblemonBattleUi$submissionGate.tryLock();
    }
}
