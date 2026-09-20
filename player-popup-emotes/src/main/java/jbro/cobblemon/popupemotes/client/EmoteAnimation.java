package jbro.cobblemon.popupemotes.client;

final class EmoteAnimation {
    static final float FADE_IN_TICKS = 2.5F;
    static final float STATIC_TICKS = 40.0F;
    static final float FADE_OUT_TICKS = 2.5F;
    static final float TOTAL_TICKS = FADE_IN_TICKS + STATIC_TICKS + FADE_OUT_TICKS;

    private EmoteAnimation() {
    }

    static float alpha(float ageTicks) {
        if (ageTicks < FADE_IN_TICKS) {
            return smooth(ageTicks / FADE_IN_TICKS);
        }
        if (ageTicks < FADE_IN_TICKS + STATIC_TICKS) {
            return 1.0F;
        }
        return 1.0F - smooth((ageTicks - FADE_IN_TICKS - STATIC_TICKS) / FADE_OUT_TICKS);
    }

    static float scale(float ageTicks) {
        if (ageTicks < FADE_IN_TICKS) {
            return 0.7F + smooth(ageTicks / FADE_IN_TICKS) * 0.3F;
        }
        if (ageTicks < FADE_IN_TICKS + STATIC_TICKS) {
            return 1.0F;
        }
        return 1.0F - smooth((ageTicks - FADE_IN_TICKS - STATIC_TICKS) / FADE_OUT_TICKS);
    }

    private static float smooth(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }
}
