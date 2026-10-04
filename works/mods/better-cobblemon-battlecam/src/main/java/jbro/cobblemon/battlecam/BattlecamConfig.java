package jbro.cobblemon.battlecam;

import java.util.Objects;

public record BattlecamConfig(
    boolean wildEnabled,
    BattlecamMode wildDefaultMode,
    boolean pveEnabled,
    BattlecamMode pveDefaultMode,
    boolean pvpEnabled,
    BattlecamMode pvpDefaultMode
) {
    public BattlecamConfig {
        wildDefaultMode = Objects.requireNonNullElse(wildDefaultMode, BattlecamMode.AUTO);
        pveDefaultMode = Objects.requireNonNullElse(pveDefaultMode, BattlecamMode.AUTO);
        pvpDefaultMode = Objects.requireNonNullElse(pvpDefaultMode, BattlecamMode.AUTO);
    }

    public static BattlecamConfig defaults() {
        return new BattlecamConfig(
            true, BattlecamMode.AUTO,
            true, BattlecamMode.AUTO,
            true, BattlecamMode.AUTO
        );
    }

    public boolean enabled(BattlecamBattleType type) {
        return switch (type) {
            case WILD -> wildEnabled;
            case PVE -> pveEnabled;
            case PVP -> pvpEnabled;
        };
    }

    public BattlecamMode defaultMode(BattlecamBattleType type) {
        return switch (type) {
            case WILD -> wildDefaultMode;
            case PVE -> pveDefaultMode;
            case PVP -> pvpDefaultMode;
        };
    }
}
