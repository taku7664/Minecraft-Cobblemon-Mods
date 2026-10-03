package jbro.cobblemon.bettermusic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

final class LastPokemonLowHpAlertPlayer {
    static final double RETRY_SECONDS = 0.70;
    private static final float PITCH = 1.0F;
    private static final float BASE_VOLUME = 0.1F;

    private final LowHpAlertLoopPlayer loopPlayer = new LowHpAlertLoopPlayer(RETRY_SECONDS);
    private LowHpAlertLoopPlayer.Backend backend;

    void tick(Minecraft client, double nowSeconds, boolean active, double volumeMultiplier, String eventId) {
        if (backend == null) {
            backend = new MinecraftBackend(client);
        }
        loopPlayer.update(nowSeconds, active, scaledVolume(volumeMultiplier), eventId, backend);
    }

    static SimpleSoundInstance loopingSound(String eventId, float volume) {
        return new SimpleSoundInstance(ResourceLocation.parse(eventId), SoundSource.MASTER,
            volume, PITCH, SoundInstance.createUnseededRandom(), true, 0,
            SoundInstance.Attenuation.NONE, 0, 0, 0, true);
    }

    private static final class MinecraftBackend implements LowHpAlertLoopPlayer.Backend {
        private final Minecraft client;
        private SoundInstance sound;

        MinecraftBackend(Minecraft client) {
            this.client = java.util.Objects.requireNonNull(client, "client");
        }

        @Override
        public boolean isPlaying() {
            return sound != null && client.getSoundManager().isActive(sound);
        }

        @Override
        public void play(String eventId, float volume) {
            sound = loopingSound(eventId, volume);
            client.getSoundManager().play(sound);
        }

        @Override
        public void stop() {
            if (sound != null) {
                client.getSoundManager().stop(sound);
                sound = null;
            }
        }
    }

    static float scaledVolume(double volumeMultiplier) {
        if (!Double.isFinite(volumeMultiplier) || volumeMultiplier < 0.0) {
            throw new IllegalArgumentException("volumeMultiplier must be non-negative and finite");
        }
        return (float) (BASE_VOLUME * volumeMultiplier);
    }
}
