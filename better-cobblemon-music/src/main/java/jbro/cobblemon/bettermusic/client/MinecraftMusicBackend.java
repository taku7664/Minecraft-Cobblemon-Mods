package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.mixin.client.ChannelAccessor;
import jbro.cobblemon.bettermusic.mixin.client.SoundEngineAccessor;
import jbro.cobblemon.bettermusic.mixin.client.SoundManagerAccessor;
import jbro.cobblemon.bettermusic.playback.FadingMusicPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public final class MinecraftMusicBackend implements FadingMusicPlayer.Backend {
    private final SoundManager soundManager;
    private final MusicLowPassFilter lowPassFilter;

    public MinecraftMusicBackend(SoundManager soundManager, Logger logger) {
        this.soundManager = java.util.Objects.requireNonNull(soundManager, "soundManager");
        this.lowPassFilter = new MusicLowPassFilter(logger);
    }

    public boolean isSoundAvailable(String sound) {
        ResourceLocation location = ResourceLocation.tryParse(sound);
        return location != null && soundManager.getAvailableSounds().contains(location);
    }

    @Override
    public FadingMusicPlayer.Handle play(FadingMusicPlayer.Track track, double initialVolume) {
        ResourceLocation location = ResourceLocation.parse(track.sound());
        var sound = new FadingMusicSoundInstance(location, initialVolume);
        soundManager.play(sound);
        return sound;
    }

    @Override
    public void setVolume(FadingMusicPlayer.Handle handle, double volume) {
        requireSound(handle).setMusicVolume(volume);
    }

    @Override
    public void setMuffle(FadingMusicPlayer.Handle handle, double amount) {
        var sound = requireSound(handle);
        var soundEngine = ((SoundManagerAccessor) soundManager)
            .betterCobblemonMusic$getSoundEngine();
        var channel = ((SoundEngineAccessor) soundEngine)
            .betterCobblemonMusic$getInstanceToChannel()
            .get(sound);
        if (channel != null) {
            channel.execute(openAlChannel -> lowPassFilter.apply(
                ((ChannelAccessor) openAlChannel).betterCobblemonMusic$getSource(),
                amount
            ));
        }
    }

    @Override
    public void stop(FadingMusicPlayer.Handle handle) {
        soundManager.stop(requireSound(handle));
    }

    @Override
    public boolean isPlaying(FadingMusicPlayer.Handle handle) {
        return soundManager.isActive(requireSound(handle));
    }

    private static FadingMusicSoundInstance requireSound(FadingMusicPlayer.Handle handle) {
        if (handle instanceof FadingMusicSoundInstance sound) {
            return sound;
        }
        throw new IllegalArgumentException("Handle does not belong to the Minecraft music backend");
    }
}
