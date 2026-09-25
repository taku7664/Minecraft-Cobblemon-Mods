package jbro.cobblemon.bettermusic.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import jbro.cobblemon.bettermusic.mixin.client.ChannelAccessor;
import jbro.cobblemon.bettermusic.mixin.client.SoundEngineAccessor;
import jbro.cobblemon.bettermusic.mixin.client.SoundManagerAccessor;
import jbro.cobblemon.bettermusic.playback.FadingMusicPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public final class MinecraftMusicBackend implements FadingMusicPlayer.Backend {
    private final SoundManager soundManager;
    private final MusicLowPassFilter lowPassFilter;
    private final Set<SoundInstance> ownedSounds = Collections.newSetFromMap(
        new IdentityHashMap<>()
    );
    private final Set<SoundInstance> muffledSounds = Collections.newSetFromMap(
        new IdentityHashMap<>()
    );

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
        ownedSounds.add(sound);
        soundManager.play(sound);
        return sound;
    }

    @Override
    public void setVolume(FadingMusicPlayer.Handle handle, double volume) {
        requireSound(handle).setMusicVolume(volume);
    }

    @Override
    public void setMuffle(double amount) {
        var soundEngine = ((SoundManagerAccessor) soundManager)
            .betterCobblemonMusic$getSoundEngine();
        var channels = ((SoundEngineAccessor) soundEngine)
            .betterCobblemonMusic$getInstanceToChannel();
        ownedSounds.removeIf(sound -> !channels.containsKey(sound));
        muffledSounds.removeIf(sound -> !channels.containsKey(sound));
        for (SoundInstance sound : ownedSounds) {
            boolean shouldApply = amount > 0.0;
            if (shouldApply) {
                muffledSounds.add(sound);
            } else if (!muffledSounds.remove(sound)) {
                continue;
            }
            channels.get(sound).execute(openAlChannel -> lowPassFilter.apply(
                ((ChannelAccessor) openAlChannel).betterCobblemonMusic$getSource(), amount
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
