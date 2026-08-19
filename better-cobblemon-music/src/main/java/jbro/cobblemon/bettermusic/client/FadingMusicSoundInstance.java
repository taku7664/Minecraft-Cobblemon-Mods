package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.playback.FadingMusicPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

final class FadingMusicSoundInstance extends AbstractTickableSoundInstance
    implements FadingMusicPlayer.Handle {

    FadingMusicSoundInstance(ResourceLocation location, double initialVolume) {
        super(
            SoundEvent.createVariableRangeEvent(location),
            SoundSource.MUSIC,
            SoundInstance.createUnseededRandom()
        );
        volume = toVolume(initialVolume);
        pitch = 1.0F;
        x = 0.0;
        y = 0.0;
        z = 0.0;
        looping = false;
        delay = 0;
        attenuation = SoundInstance.Attenuation.NONE;
        relative = true;
    }

    @Override
    public void tick() {
    }

    void setMusicVolume(double volume) {
        this.volume = toVolume(volume);
    }

    private static float toVolume(double volume) {
        if (!Double.isFinite(volume) || volume < 0.0) {
            throw new IllegalArgumentException("volume must be finite and non-negative");
        }
        return (float) volume;
    }
}
