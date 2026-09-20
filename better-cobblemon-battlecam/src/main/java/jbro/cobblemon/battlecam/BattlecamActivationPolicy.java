package jbro.cobblemon.battlecam;

final class BattlecamActivationPolicy {
    private BattlecamActivationPolicy() {
    }

    static boolean shouldActivate(
        boolean spectating,
        boolean hasSubjects,
        BattlecamMode mode,
        boolean onlyWhenBattleScreenOpen,
        boolean battleScreenOpen
    ) {
        return spectating
            && hasSubjects
            && mode != BattlecamMode.OFF
            && (!onlyWhenBattleScreenOpen || battleScreenOpen);
    }
}
