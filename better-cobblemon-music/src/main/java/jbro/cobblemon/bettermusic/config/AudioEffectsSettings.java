package jbro.cobblemon.bettermusic.config;

public record AudioEffectsSettings(
    boolean hitSoundsEnabled,
    double hitSoundVolume,
    boolean lastPokemonHpEffectsEnabled,
    double lastPokemonHpEffectVolume,
    boolean underwaterEffectsEnabled,
    double underwaterEffectStrength
) {
    public static final double MAX_VOLUME = 2.0;
    public static final double DEFAULT_UNDERWATER_STRENGTH = 0.35;

    public AudioEffectsSettings {
        requireVolume(hitSoundVolume, "hitSoundVolume");
        requireVolume(lastPokemonHpEffectVolume, "lastPokemonHpEffectVolume");
        if (!Double.isFinite(underwaterEffectStrength) || underwaterEffectStrength < 0.0
            || underwaterEffectStrength > 1.0) {
            throw new IllegalArgumentException("underwaterEffectStrength must be finite and between 0.0 and 1.0");
        }
    }

    public AudioEffectsSettings(boolean hitSoundsEnabled, double hitSoundVolume,
        boolean lastPokemonHpEffectsEnabled, double lastPokemonHpEffectVolume) {
        this(hitSoundsEnabled, hitSoundVolume, lastPokemonHpEffectsEnabled, lastPokemonHpEffectVolume,
            true, DEFAULT_UNDERWATER_STRENGTH);
    }

    public static AudioEffectsSettings defaults() {
        return new AudioEffectsSettings(true, 1.0, true, 1.0);
    }

    private static void requireVolume(double volume, String name) {
        if (!Double.isFinite(volume) || volume < 0.0 || volume > MAX_VOLUME) {
            throw new IllegalArgumentException(name + " must be finite and between 0.0 and 2.0");
        }
    }
}
