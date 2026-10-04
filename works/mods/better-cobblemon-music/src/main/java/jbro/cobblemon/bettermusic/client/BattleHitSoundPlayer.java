package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.audio.HitEffectiveness;
import jbro.cobblemon.bettermusic.catalog.MusicCatalog;
import jbro.cobblemon.bettermusic.config.AudioEffectsSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class BattleHitSoundPlayer {
    private static volatile AudioEffectsSettings settings = AudioEffectsSettings.defaults();
    private static volatile MusicCatalog.AudioEvents audioEvents;

    private BattleHitSoundPlayer() {
    }

    public static void play(HitEffectiveness effectiveness) {
        AudioEffectsSettings current = settings;
        if (!current.hitSoundsEnabled() || current.hitSoundVolume() == 0.0) {
            return;
        }
        MusicCatalog.AudioEvents currentEvents = audioEvents;
        if (currentEvents == null) {
            return;
        }
        ResourceLocation location = ResourceLocation.parse(event(effectiveness, currentEvents));
        SoundEvent sound = SoundEvent.createVariableRangeEvent(location);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            sound,
            1.0F,
            (float) current.hitSoundVolume()
        ));
    }

    static void configure(AudioEffectsSettings newSettings, MusicCatalog.AudioEvents newAudioEvents) {
        settings = java.util.Objects.requireNonNull(newSettings, "newSettings");
        audioEvents = java.util.Objects.requireNonNull(newAudioEvents, "newAudioEvents");
    }

    static String event(HitEffectiveness effectiveness, MusicCatalog.AudioEvents events) {
        return switch (java.util.Objects.requireNonNull(effectiveness, "effectiveness")) {
            case NORMAL -> events.hitNormal();
            case SUPER_EFFECTIVE -> events.hitSuperEffective();
            case NOT_VERY_EFFECTIVE -> events.hitNotVeryEffective();
        };
    }
}
