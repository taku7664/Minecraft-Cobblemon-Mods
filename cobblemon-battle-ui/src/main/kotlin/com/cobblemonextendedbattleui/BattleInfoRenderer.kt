package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Renders battle information overlays.
 */
object BattleInfoRenderer {

    fun render(context: DrawContext) {
        // Clear stale move tooltip state when BattleMoveSelection stops rendering.
        // Must run before panels check shouldHandleFontInput() and before the
        // F1 early return (otherwise staleness is never detected while HUD is hidden).
        MoveTooltipRenderer.resetIfStale()

        // Respect F1 to hide HUD
        if (MinecraftClient.getInstance().options.hudHidden) return

        // Critical: Check for battle changes FIRST when any feature needs state tracking
        // This ensures state is cleared when a new battle starts, regardless of which features are enabled
        if (PanelConfig.needsBattleStateTracking()) {
            CobblemonClient.battle?.let { battle ->
                BattleStateTracker.checkBattleChanged(battle.battleId)
            }
        }

        if (PanelConfig.enableBattleInfoPanelEffective) {
            BattleInfoPanel.update()
        }
        if (PanelConfig.enableBattleLogEffective) {
            BattleLogWidget.render(context)
        }
    }
}
