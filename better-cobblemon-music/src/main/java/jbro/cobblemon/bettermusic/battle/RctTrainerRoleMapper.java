package jbro.cobblemon.bettermusic.battle;

import java.util.Locale;
import java.util.Optional;

public final class RctTrainerRoleMapper {
    private RctTrainerRoleMapper() {
    }

    public static Optional<String> map(String rctTypeId) {
        if (rctTypeId == null || rctTypeId.isBlank()) {
            return Optional.empty();
        }
        return switch (rctTypeId.toLowerCase(Locale.ROOT)) {
            case "leader" -> Optional.of("gym");
            case "e4" -> Optional.of("elite");
            case "champ" -> Optional.of("champion");
            case "rival" -> Optional.of("rival");
            default -> Optional.empty();
        };
    }
}
