package jbro.cobblemon.bettermusic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

final class LastPokemonHeartbeatPlayer {
    private static final double CADENCE_SECONDS = 1.0;
    private static final float PITCH = 1.0F;
    private static final float BASE_VOLUME = 0.35F;

    private final HeartbeatPulseScheduler scheduler = new HeartbeatPulseScheduler(CADENCE_SECONDS);

    void tick(Minecraft client, double nowSeconds, boolean active, double volumeMultiplier) {
        if (!scheduler.shouldPulse(nowSeconds, active)) {
            return;
        }
        float volume = scaledVolume(volumeMultiplier);
        if (volume == 0.0F) {
            return;
        }
        client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.WARDEN_HEARTBEAT,
            PITCH,
            volume
        ));
    }

    static float scaledVolume(double volumeMultiplier) {
        if (!Double.isFinite(volumeMultiplier) || volumeMultiplier < 0.0) {
            throw new IllegalArgumentException("volumeMultiplier must be non-negative and finite");
        }
        return (float) (BASE_VOLUME * volumeMultiplier);
    }
}
