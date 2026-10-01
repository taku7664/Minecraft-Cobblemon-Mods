package jbro.cobblemon.ui.extended.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleMessageHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import jbro.cobblemon.ui.extended.BattleLog;
import jbro.cobblemon.ui.extended.BattleDialogue;
import jbro.cobblemon.ui.extended.BattleMessageInterceptor;
import jbro.cobblemon.ui.extended.PanelConfig;
import net.minecraft.client.MinecraftClient;

/**
 * Mixin to intercept battle messages for state tracking and custom battle log.
 *
 * Conditionally processes messages based on enabled features:
 * - BattleMessageInterceptor: Updates BattleStateTracker (needed for panel + tooltips)
 * - BattleLog: Stores messages for custom battle log widget
 * - Cancels default handler when our battle log is enabled (hides Cobblemon's chat log)
 */
@Mixin(value = BattleMessageHandler.class, remap = false)
public class BattleMessageHandlerMixin {

    /**
     * Inject at the start of handle() to process messages before they're displayed.
     * Only processes tracking that's needed for enabled features.
     */
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleMessagePacket packet, MinecraftClient client, CallbackInfo ci) {
        // Process messages for state tracking only if panel or team indicators are enabled
        boolean needsStateTracking = PanelConfig.INSTANCE.needsBattleStateTracking();
        if (needsStateTracking) {
            BattleMessageInterceptor.INSTANCE.processMessages(packet.getMessages());
        }

        // Store messages in battle log only if the battle log feature is enabled
        if (PanelConfig.INSTANCE.getEnableBattleLogEffective()) {
            jbro.cobblemon.ui.extended.ui.transcript.TranscriptSources.INSTANCE.capture(packet.getMessages());
            BattleDialogue.INSTANCE.enqueue(packet.getMessages());
            // Dialogue owns battle message presentation while this feature is enabled.
            ci.cancel();
        }
    }
}
