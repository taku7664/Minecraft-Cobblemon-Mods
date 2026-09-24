package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.audio.BattleHitSoundIds;
import jbro.cobblemon.bettermusic.audio.HitEffectiveness;
import jbro.cobblemon.bettermusic.config.AudioEffectsSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class BattleHitSoundPlayer {
    private static volatile AudioEffectsSettings settings = AudioEffectsSettings.defaults();

    private BattleHitSoundPlayer() {
    }

    public static void play(HitEffectiveness effectiveness) {
        AudioEffectsSettings current = settings;
        if (!current.hitSoundsEnabled() || current.hitSoundVolume() == 0.0) {
            return;
        }
        ResourceLocation location = ResourceLocation.parse(BattleHitSoundIds.event(effectiveness));
        SoundEvent sound = SoundEvent.createVariableRangeEvent(location);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            sound,
            1.0F,
            (float) current.hitSoundVolume()
        ));
    }

    static void configure(AudioEffectsSettings newSettings) {
        settings = java.util.Objects.requireNonNull(newSettings, "newSettings");
    }
}
