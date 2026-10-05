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
    private final MusicReverbEffect reverbEffect;
    private final MusicDistortionEffect distortionEffect;
    private double distortionAmount;
    private final Set<SoundInstance> ownedSounds = Collections.newSetFromMap(
        new IdentityHashMap<>()
    );
    private final Set<SoundInstance> affectedSounds = Collections.newSetFromMap(
        new IdentityHashMap<>()
    );

    public MinecraftMusicBackend(SoundManager soundManager, Logger logger) {
        this.soundManager = java.util.Objects.requireNonNull(soundManager, "soundManager");
        this.lowPassFilter = new MusicLowPassFilter(logger);
        this.reverbEffect = new MusicReverbEffect(logger);
        this.distortionEffect = new MusicDistortionEffect(logger);
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
    public void setEffects(double muffleAmount, double underwaterAmount) {
        double underwaterIntensity = underwaterIntensity(underwaterAmount);
        double lowPassAmount = Math.max(muffleAmount, underwaterIntensity);
        double distortion = distortionAmount;
        var soundEngine = ((SoundManagerAccessor) soundManager)
            .betterCobblemonMusic$getSoundEngine();
        var channels = ((SoundEngineAccessor) soundEngine)
            .betterCobblemonMusic$getInstanceToChannel();
        for (SoundInstance sound : ownedSounds) {
            var channel = channels.get(sound);
            if (channel == null) {
                continue;
            }
            boolean shouldApply = lowPassAmount > 0.0 || underwaterIntensity > 0.0 || distortionAmount > 0.0;
            if (shouldApply) {
                affectedSounds.add(sound);
            } else if (!affectedSounds.remove(sound)) {
                continue;
            }
            channel.execute(openAlChannel -> {
                int source = ((ChannelAccessor) openAlChannel).betterCobblemonMusic$getSource();
                lowPassFilter.apply(source, lowPassAmount);
                reverbEffect.apply(source, underwaterIntensity);
                distortionEffect.apply(source, distortion);
            });
        }
    }

    @Override
    public void setDistortion(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("distortion must be finite and between zero and one");
        }
        distortionAmount = amount;
    }

    static double underwaterIntensity(double strength) {
        if (!Double.isFinite(strength) || strength < 0.0 || strength > 1.0) {
            throw new IllegalArgumentException("underwater strength must be finite and between zero and one");
        }
        // Preserve both slider endpoints while making its modest default audible.
        return Math.sqrt(strength);
    }

    @Override
    public void stop(FadingMusicPlayer.Handle handle) {
        FadingMusicSoundInstance sound = requireSound(handle);
        ownedSounds.remove(sound);
        affectedSounds.remove(sound);
        soundManager.stop(sound);
    }

    @Override
    public boolean isPlaying(FadingMusicPlayer.Handle handle) {
        FadingMusicSoundInstance sound = requireSound(handle);
        // Status queries must not drop a sound that is still loading from effect tracking.
        // The player releases ended tracks through stop(), including natural endings.
        return soundManager.isActive(sound);
    }

    private static FadingMusicSoundInstance requireSound(FadingMusicPlayer.Handle handle) {
        if (handle instanceof FadingMusicSoundInstance sound) {
            return sound;
        }
        throw new IllegalArgumentException("Handle does not belong to the Minecraft music backend");
    }
}
