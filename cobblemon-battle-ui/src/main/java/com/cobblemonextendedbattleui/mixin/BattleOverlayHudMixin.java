package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.client.battle.ClientBallDisplay;
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.status.PersistentStatus;
import jbro.cobblemon.battleui.extended.ui.shared.BattleHudRenderer;
import kotlin.Triple;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Cobblemon's battle data/position animation while restyling its ordinary HUD. */
@Mixin(value = BattleOverlay.class, remap = false)
public abstract class BattleOverlayHudMixin {
    @Inject(method = "drawBattleTile", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$drawHud(DrawContext context, float x, float y, float partialTicks,
            boolean reversed, Species species, int level, MutableText displayName, Gender gender,
            PersistentStatus status, PosableState state, Triple<Float, Float, Float> colour,
            float opacity, ClientBallDisplay ballState, int maxHealth, float health,
            boolean isSelected, boolean isHovered, boolean isCompact, MutableText actorDisplayName,
            boolean isFlatHealth, PokedexEntryProgress dexState, CallbackInfo ci) {
        // The native path alone knows how to animate a Poké Ball during capture.
        if (ballState != null) return;
        BattleHudRenderer.draw(context, x, y, reversed, species, level, displayName, gender,
                status, state, opacity, maxHealth, health, isSelected, isHovered, isCompact,
                actorDisplayName, isFlatHealth, dexState);
        ci.cancel();
    }
}
