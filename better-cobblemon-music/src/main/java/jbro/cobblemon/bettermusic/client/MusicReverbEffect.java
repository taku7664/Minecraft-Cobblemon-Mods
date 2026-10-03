package jbro.cobblemon.bettermusic.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

/** One shared EFX send for Music-owned sources; all calls run on the OpenAL channel thread. */
final class MusicReverbEffect {
    private final Logger logger;
    private int effect;
    private int slot;
    private boolean disabled;
    private boolean failureReported;
    private boolean applicationReported;

    MusicReverbEffect(Logger logger) {
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    void apply(int source, double amount) {
        // A failed attachment must not prevent a later exit/disable from detaching it.
        if (disabled && amount > 0.0) {
            return;
        }
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("amount must be finite and between zero and one");
        }
        if (amount == 0.0 && slot == 0) {
            return;
        }
        try {
            if (ALC.getCapabilities() == null || !ALC.getCapabilities().ALC_EXT_EFX) {
                disable("OpenAL EFX is unavailable; underwater reverb is disabled", null);
                return;
            }
            AL10.alGetError();
            if (amount == 0.0) {
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
            } else {
                ensureReverb();
                EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, (float) amount);
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    slot, 0, EXTEfx.AL_FILTER_NULL);
            }
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                disable("OpenAL rejected the underwater reverb (error " + error + ")", null);
                return;
            }
            if (amount > 0.0 && !applicationReported) {
                applicationReported = true;
                logger.info("Attached the underwater OpenAL reverb to the music source");
            }
        } catch (RuntimeException | LinkageError failure) {
            disable("Could not apply the underwater reverb", failure);
        }
    }

    private void ensureReverb() {
        if (effect != 0 && slot != 0 && EXTEfx.alIsEffect(effect) && EXTEfx.alIsAuxiliaryEffectSlot(slot)) {
            return;
        }
        // Invalid IDs after a sound-device reload belong to the old context and are not deleted here.
        effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 0.9F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, 0.45F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.3F);
        slot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
    }

    private void disable(String message, Throwable failure) {
        disabled = true;
        if (failureReported) {
            return;
        }
        failureReported = true;
        if (failure == null) {
            logger.warn(message);
        } else {
            logger.warn(message, failure);
        }
    }
}
