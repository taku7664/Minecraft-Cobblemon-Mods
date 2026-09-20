package jbro.cobblemon.battlecam;

public enum BattlecamMode {
    OFF,
    AUTO,
    MANUAL;

    public BattlecamMode next() {
        return switch (this) {
            case OFF -> AUTO;
            case AUTO -> MANUAL;
            case MANUAL -> OFF;
        };
    }
}
