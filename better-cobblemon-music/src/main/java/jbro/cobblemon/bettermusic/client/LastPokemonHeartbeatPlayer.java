package jbro.cobblemon.bettermusic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

final class LastPokemonHeartbeatPlayer {
    private static final double CADENCE_SECONDS = 1.0;
    private static final float PITCH = 1.0F;
    private static final float VOLUME = 0.35F;

    private final HeartbeatPulseScheduler scheduler = new HeartbeatPulseScheduler(CADENCE_SECONDS);

    void tick(Minecraft client, double nowSeconds, boolean active) {
        if (!scheduler.shouldPulse(nowSeconds, active)) {
            return;
        }
        client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.WARDEN_HEARTBEAT,
            PITCH,
            VOLUME
        ));
    }
}
