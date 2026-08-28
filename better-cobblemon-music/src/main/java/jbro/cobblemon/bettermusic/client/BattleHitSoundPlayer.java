package jbro.cobblemon.bettermusic.client;

import jbro.cobblemon.bettermusic.audio.BattleHitSoundIds;
import jbro.cobblemon.bettermusic.audio.HitEffectiveness;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class BattleHitSoundPlayer {
    private BattleHitSoundPlayer() {
    }

    public static void play(HitEffectiveness effectiveness) {
        ResourceLocation location = ResourceLocation.parse(BattleHitSoundIds.event(effectiveness));
        SoundEvent sound = SoundEvent.createVariableRangeEvent(location);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F));
    }
}
