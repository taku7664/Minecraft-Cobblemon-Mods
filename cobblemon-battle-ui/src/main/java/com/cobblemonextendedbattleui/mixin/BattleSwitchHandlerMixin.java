package jbro.cobblemon.battleui.extended.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.net.battle.BattleSwitchPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleSwitchPokemonPacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import jbro.cobblemon.battleui.extended.BattleStateTracker;
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI;
import jbro.cobblemon.battleui.extended.DamageTracker;
import jbro.cobblemon.battleui.extended.PanelConfig;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Intercepts Pokemon switch-in events to:
 * - Pre-populate HP baselines in DamageTracker (when battle log enabled)
 * - Register Pokemon with BattleStateTracker (when panel/tooltips enabled)
 */
@Mixin(value = BattleSwitchPokemonHandler.class, remap = false)
public class BattleSwitchHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleSwitchPokemonPacket packet, MinecraftClient client, CallbackInfo ci) {
        boolean needsStateTracking = PanelConfig.INSTANCE.needsBattleStateTracking();
        boolean needsDamageTracking = PanelConfig.INSTANCE.needsDamageTracking();

        // Skip if no tracking is needed
        if (!needsStateTracking && !needsDamageTracking) return;

        try {
            BattleInitializePacket.ActiveBattlePokemonDTO newPokemon = packet.getNewPokemon();
            if (newPokemon == null) return;

            UUID uuid = newPokemon.getUuid();

            // Pre-populate the HP tracker with the switch-in HP value (only if battle log needs it)
            if (needsDamageTracking) {
                float hpValue = newPokemon.getHpValue();
                float maxHp = newPokemon.getMaxHp();
                boolean isFlat = newPokemon.isFlatHp();
                DamageTracker.INSTANCE.initializeHpFromSwitch(uuid, hpValue, maxHp, isFlat);
            }

            // Register Pokemon with BattleStateTracker for name->UUID lookup (for stat tracking)
            if (needsStateTracking) {
                String name = newPokemon.getDisplayName() != null ? newPokemon.getDisplayName().getString() : "Unknown";

                // Determine if this Pokemon is ally or opponent by checking the pnx actor
                Boolean isAlly = determineIfAlly(packet, client);
                if (isAlly != null) {
                    BattleStateTracker.INSTANCE.registerPokemon(uuid, name, isAlly);
                }
            }
        } catch (Exception e) {
            CobblemonExtendedBattleUI.INSTANCE.getLOGGER().warn("Failed to update Battle UI switch state", e);
        }
    }

    /**
     * Determine if the switching Pokemon is on the ally or opponent side.
     * Uses battle.getPokemonFromPNX() to find the side, then compares with player's side.
     */
    private Boolean determineIfAlly(BattleSwitchPokemonPacket packet, MinecraftClient client) {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return null;

            UUID playerUUID = client.getSession().getUuidOrNull();
            if (playerUUID == null) return null;

            // Check if player is in side1 or side2
            boolean playerInSide1 = battle.getSide1().getActors().stream()
                    .anyMatch(actor -> playerUUID.equals(actor.getUuid()));
            boolean playerInSide2 = battle.getSide2().getActors().stream()
                    .anyMatch(actor -> playerUUID.equals(actor.getUuid()));

            // Determine which side the switch is on by checking if the actor is in side1
            String pnx = packet.getPnx();
            var result = battle.getPokemonFromPNX(pnx);
            if (result == null) return null;

            var actor = result.getFirst();
            if (actor == null) return null;

            UUID actorUuid = actor.getUuid();
            boolean switchInSide1 = battle.getSide1().getActors().stream()
                    .anyMatch(a -> actorUuid.equals(a.getUuid()));

            // Spectators: Cobblemon renders side2 on the left, matching BattleOverlay.
            if (!playerInSide1 && !playerInSide2) {
                return !switchInSide1; // side2 is on the left for spectators
            }

            // If player is in side1, side1 switches are ally
            // If player is in side2, side2 switches are ally
            if (playerInSide1) {
                return switchInSide1;
            } else {
                return !switchInSide1;
            }
        } catch (Exception e) {
            CobblemonExtendedBattleUI.INSTANCE.getLOGGER().warn("Could not determine the side of a switched Pokemon", e);
            return null;
        }
    }
}
