package jbro.cobblemon.battlecam;

final class BattlecamActivationPolicy {
    private BattlecamActivationPolicy() {
    }

    static boolean shouldActivate(
        boolean enabledForBattleType,
        boolean hasSubjects,
        BattlecamMode mode
    ) {
        return enabledForBattleType
            && hasSubjects
            && mode != BattlecamMode.OFF;
    }
}
