package jbro.cobblemon.bettermusic.audio;

public final class BattleHitSoundIds {
    private static final String PREFIX = "cobleserver:battle.hit.";

    private BattleHitSoundIds() {
    }

    public static String event(HitEffectiveness effectiveness) {
        return PREFIX + switch (effectiveness) {
            case NORMAL -> "normal";
            case SUPER_EFFECTIVE -> "super_effective";
            case NOT_VERY_EFFECTIVE -> "not_very_effective";
        };
    }
}
