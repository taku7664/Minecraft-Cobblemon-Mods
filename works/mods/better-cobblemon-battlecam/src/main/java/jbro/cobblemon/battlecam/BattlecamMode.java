package jbro.cobblemon.battlecam;

public enum BattlecamMode {
    OFF,
    AUTO,
    MANUAL,
    /** The main games' fixed view from behind the player's own Pokemon. */
    CLASSIC;

    public BattlecamMode next() {
        return switch (this) {
            case OFF -> AUTO;
            case AUTO -> MANUAL;
            case MANUAL -> CLASSIC;
            case CLASSIC -> OFF;
        };
    }
}
