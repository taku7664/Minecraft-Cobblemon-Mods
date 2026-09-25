package jbro.cobblemon.battleui.extended.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import jbro.cobblemon.battleui.extended.BattleStateTracker;
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI;
import jbro.cobblemon.battleui.extended.DamageTracker;
import jbro.cobblemon.battleui.extended.BattleDialogue;
import jbro.cobblemon.battleui.extended.PanelConfig;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts battle initialization to pre-populate HP baselines and register Pokemon.
 *
 * Conditionally initializes tracking based on enabled features:
 * - DamageTracker HP baselines: Only when battle log is enabled
 * - BattleStateTracker Pokemon registration: Only when panel or team indicators are enabled
 */
@Mixin(value = BattleInitializeHandler.class, remap = false)
public class BattleInitializeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleInitializePacket packet, MinecraftClient client, CallbackInfo ci) {
        BattleDialogue.INSTANCE.clear();
        boolean needsStateTracking = PanelConfig.INSTANCE.needsBattleStateTracking();
        boolean needsDamageTracking = PanelConfig.INSTANCE.needsDamageTracking();

        // Skip entirely if no features need initialization
        if (!needsStateTracking && !needsDamageTracking) return;

        try {
            // Determine if player is spectating
            UUID playerUUID = client.getSession().getUuidOrNull();
            boolean isPlayerInSide1 = isPlayerInSide(packet.getSide1(), playerUUID);
            boolean isPlayerInSide2 = isPlayerInSide(packet.getSide2(), playerUUID);
            boolean isSpectating = !isPlayerInSide1 && !isPlayerInSide2;

            // Cobblemon's BattleOverlay swaps sides based on player presence:
            // - If player is in side1: side1 is LEFT (ally), side2 is RIGHT
            // - If player is in side2: side2 is LEFT (ally), side1 is RIGHT
            // - If spectating: side2 is LEFT (ally), side1 is RIGHT
            // So side1 is "ally" only when the player is actually in side1
            boolean side1IsAlly = isPlayerInSide1;

            // Only set up BattleStateTracker if needed
            if (needsStateTracking) {
                BattleStateTracker.INSTANCE.setSpectating(isSpectating);

                List<String> side1PlayerNames = getActorNames(packet.getSide1());
                List<String> side2PlayerNames = getActorNames(packet.getSide2());

                if (!side1PlayerNames.isEmpty() && !side2PlayerNames.isEmpty()) {
                    if (side1IsAlly) {
                        BattleStateTracker.INSTANCE.setPlayerNames(side1PlayerNames, side2PlayerNames);
                    } else {
                        BattleStateTracker.INSTANCE.setPlayerNames(side2PlayerNames, side1PlayerNames);
                    }
                }
            }

            // Initialize Pokemon from both sides (conditionally based on needed tracking)
            initializePokemonFromSide(packet.getSide1(), side1IsAlly, needsStateTracking, needsDamageTracking);
            initializePokemonFromSide(packet.getSide2(), !side1IsAlly, needsStateTracking, needsDamageTracking);
        } catch (Exception e) {
            CobblemonExtendedBattleUI.INSTANCE.getLOGGER().warn("Failed to initialize Battle UI state", e);
        }
    }

    private boolean isPlayerInSide(BattleInitializePacket.BattleSideDTO side, UUID playerUUID) {
        if (side == null || playerUUID == null) return false;
        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor != null && playerUUID.equals(actor.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private List<String> getActorNames(BattleInitializePacket.BattleSideDTO side) {
        List<String> names = new ArrayList<>();
        if (side == null) return names;
        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor != null && actor.getDisplayName() != null) {
                names.add(actor.getDisplayName().getString());
            }
        }
        return names;
    }

    private void initializePokemonFromSide(BattleInitializePacket.BattleSideDTO side, boolean isAlly,
                                           boolean needsStateTracking, boolean needsDamageTracking) {
        if (side == null) return;

        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor == null) continue;
            String ownerName = actor.getDisplayName() != null ? actor.getDisplayName().getString() : null;

            for (BattleInitializePacket.ActiveBattlePokemonDTO pokemon : actor.getActivePokemon()) {
                if (pokemon == null) continue;

                UUID uuid = pokemon.getUuid();

                // Pre-populate the HP tracker only if battle log needs damage percentages
                if (needsDamageTracking) {
                    float hpValue = pokemon.getHpValue();
                    float maxHp = pokemon.getMaxHp();
                    boolean isFlat = pokemon.isFlatHp();
                    DamageTracker.INSTANCE.initializeHpFromSwitch(uuid, hpValue, maxHp, isFlat);
                }

                // Register Pokemon with BattleStateTracker only if panel/tooltips need it
                if (needsStateTracking) {
                    String name = pokemon.getDisplayName() != null ? pokemon.getDisplayName().getString() : "Unknown";
                    BattleStateTracker.INSTANCE.registerPokemon(uuid, name, isAlly, ownerName);
                }
            }
        }
    }
}
