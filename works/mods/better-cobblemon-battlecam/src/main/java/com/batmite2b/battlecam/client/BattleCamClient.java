/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.fabricmc.api.ClientModInitializer
 *  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
 *  net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
 *  net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.text.Text
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamConfig;
import com.batmite2b.battlecam.client.BattleCamHud;
import com.batmite2b.battlecam.client.BattleCamKeybinds;
import com.batmite2b.battlecam.client.BattleCamPerspectiveController;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleGimmickMessageWatcher;
import com.batmite2b.battlecam.client.BattleScreenUtil;
import com.batmite2b.battlecam.client.BattleSwitchWatcher;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.batmite2b.battlecam.client.CobblemonBattleContextWatcher;
import com.batmite2b.battlecam.client.MegaShowdownCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BattleCamClient
implements ClientModInitializer {
    public static final String MOD_ID = "battlecam";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"battlecam");
    public static final BattleCamConfig CONFIG = BattleCamConfig.loadOrCreate();
    public static final BattleCamState STATE = new BattleCamState();
    public static final MegaShowdownCompat MEGA_SHOWDOWN_COMPAT = new MegaShowdownCompat();
    public static final BattleSwitchWatcher SWITCH_WATCHER = new BattleSwitchWatcher();
    public static final BattleGimmickMessageWatcher GIMMICK_MESSAGE_WATCHER = new BattleGimmickMessageWatcher();
    private boolean pendingJoinHint = false;

    public void onInitializeClient() {
        LOGGER.info("BattleCam starting...");
        BattleCamKeybinds.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            this.pendingJoinHint = true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> BattleCamHud.render(drawContext));
    }

    private void onClientTick(MinecraftClient client) {
        if (this.pendingJoinHint && client.player != null) {
            this.pendingJoinHint = false;
            client.player.sendMessage((Text)Text.translatable((String)"message.battlecam.join_hint"), false);
        }
        BattleViewContext context = CobblemonBattleContextWatcher.poll();
        STATE.setContext(context, CobblemonBattleContextWatcher.currentBattleId());
        STATE.updateOwnBattleUiPause(BattleScreenUtil.isCobblemonBattleScreenOpen(client.currentScreen));
        BattleCamKeybinds.handleInput(STATE);
        MEGA_SHOWDOWN_COMPAT.tick(client, STATE);
        SWITCH_WATCHER.tick(client, STATE);
        STATE.tick(client);
        BattleCamPerspectiveController.update(client);
    }
}
