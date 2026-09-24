package jbro.cobblemon.bettermusic.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

final class MusicLowPassFilter {
    private static final float FULL_MUFFLE_GAIN = 0.65F;
    private static final float FULL_MUFFLE_GAIN_HF = 0.02F;

    private final Logger logger;
    private int filter;
    private boolean disabled;
    private boolean failureReported;
    private boolean applicationReported;

    MusicLowPassFilter(Logger logger) {
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    void apply(int source, double amount) {
        if (disabled) {
            return;
        }
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("amount must be finite and between zero and one");
        }
        try {
            if (amount == 0.0 && filter == 0) {
                return;
            }
            if (ALC.getCapabilities() == null || !ALC.getCapabilities().ALC_EXT_EFX) {
                disable("OpenAL EFX is unavailable; last-Pokémon music muffling is disabled", null);
                return;
            }
            int activeFilter = ensureFilter();
            if (amount == 0.0) {
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            } else {
                EXTEfx.alFilterf(activeFilter, EXTEfx.AL_LOWPASS_GAIN, gain(amount));
                EXTEfx.alFilterf(
                    activeFilter,
                    EXTEfx.AL_LOWPASS_GAINHF,
                    gainHighFrequency(amount)
                );
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, activeFilter);
            }
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                disable("OpenAL rejected the last-Pokémon music filter (error " + error + ")", null);
                return;
            }
            if (amount > 0.0 && !applicationReported) {
                applicationReported = true;
                logger.info("Attached the last-Pokémon OpenAL low-pass filter to the music source");
            }
        } catch (RuntimeException | LinkageError failure) {
            disable("Could not apply the last-Pokémon music filter", failure);
        }
    }

    private int ensureFilter() {
        if (filter != 0 && EXTEfx.alIsFilter(filter)) {
            return filter;
        }
        AL10.alGetError();
        filter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        return filter;
    }

    static float gainHighFrequency(double amount) {
        requireAmount(amount);
        return interpolateGain(FULL_MUFFLE_GAIN_HF, amount);
    }

    static float gain(double amount) {
        requireAmount(amount);
        return interpolateGain(FULL_MUFFLE_GAIN, amount);
    }

    private static float interpolateGain(float fullMuffleGain, double amount) {
        return (float) (1.0 + (fullMuffleGain - 1.0) * amount);
    }

    private static void requireAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("amount must be finite and between zero and one");
        }
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
