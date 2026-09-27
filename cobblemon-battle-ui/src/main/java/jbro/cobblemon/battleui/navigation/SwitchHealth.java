package jbro.cobblemon.battleui.navigation;

/** Parses the public HP token provided to Cobblemon's party-selection UI. */
public final class SwitchHealth {
    private SwitchHealth() {
    }

    public static float ratio(String condition, int maxHealth) {
        if (condition == null || maxHealth <= 0) return 0f;
        String hpToken = condition.split(" ", 2)[0].split("/", 2)[0];
        try {
            return Math.max(0f, Math.min(1f, Integer.parseInt(hpToken) / (float) maxHealth));
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }
}
