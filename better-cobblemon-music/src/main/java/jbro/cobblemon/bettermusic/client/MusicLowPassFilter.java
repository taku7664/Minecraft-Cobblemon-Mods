package jbro.cobblemon.bettermusic.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

final class MusicLowPassFilter {
    private static final float MAXIMUM_MUFFLE_GAIN_HF = 0.08F;

    private final Logger logger;
    private int filter;
    private boolean disabled;
    private boolean failureReported;

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
                float gainHighFrequency = gainHighFrequency(amount);
                EXTEfx.alFilterf(activeFilter, EXTEfx.AL_LOWPASS_GAINHF, gainHighFrequency);
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, activeFilter);
            }
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                disable("OpenAL rejected the last-Pokémon music filter (error " + error + ")", null);
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
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
        return filter;
    }

    static float gainHighFrequency(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("amount must be finite and between zero and one");
        }
        return (float) (1.0 + (MAXIMUM_MUFFLE_GAIN_HF - 1.0) * amount);
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
