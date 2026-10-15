package jbro.cobblemon.ui.extended;

import java.util.Locale;
import java.util.Map;

/** Max Move power from the original move, matching Cobblemon 1.8.1's bundled Showdown 18. */
public final class MaxMovePowerPreview {
    private MaxMovePowerPreview() {}

    // Explicit maxMove.basePower values take precedence over the type/power conversion table.
    private static final Map<String, Integer> OVERRIDES = Map.ofEntries(
        Map.entry("bonemerang", 130),
        Map.entry("bonerush", 130),
        Map.entry("bulletseed", 130),
        Map.entry("cometpunch", 100),
        Map.entry("counter", 75),
        Map.entry("crushgrip", 140),
        Map.entry("doublehit", 120),
        Map.entry("doubleironbash", 140),
        Map.entry("doublekick", 80),
        Map.entry("dragondarts", 130),
        Map.entry("dualchop", 130),
        Map.entry("dualwingbeat", 130),
        Map.entry("electroball", 130),
        Map.entry("endeavor", 130),
        Map.entry("fissure", 130),
        Map.entry("flail", 130),
        Map.entry("frustration", 130),
        Map.entry("furyswipes", 100),
        Map.entry("geargrind", 130),
        Map.entry("grassknot", 130),
        Map.entry("guillotine", 130),
        Map.entry("gyroball", 130),
        Map.entry("heatcrash", 130),
        Map.entry("heavyslam", 130),
        Map.entry("horndrill", 130),
        Map.entry("iciclespear", 130),
        Map.entry("magnitude", 140),
        Map.entry("multiattack", 95),
        Map.entry("naturalgift", 130),
        Map.entry("pinmissile", 130),
        Map.entry("powertrip", 130),
        Map.entry("punishment", 130),
        Map.entry("return", 130),
        Map.entry("risingvoltage", 140),
        Map.entry("rockblast", 130),
        Map.entry("scaleshot", 130),
        Map.entry("seismictoss", 75),
        Map.entry("sheercold", 130),
        Map.entry("spikecannon", 120),
        Map.entry("storedpower", 130),
        Map.entry("surgingstrikes", 130),
        Map.entry("tachyoncutter", 140),
        Map.entry("tailslap", 130),
        Map.entry("terrainpulse", 130),
        Map.entry("tripleaxel", 140),
        Map.entry("triplekick", 80),
        Map.entry("trumpcard", 130),
        Map.entry("twineedle", 100),
        Map.entry("weatherball", 130),
        Map.entry("wringout", 140)
    );

    public static boolean isMaxMove(String moveId) {
        String id = normalize(moveId);
        return id.startsWith("max") || id.startsWith("gmax");
    }

    /** Null means the original move is unavailable; never expose a Max Move's placeholder power. */
    public static Integer power(String displayedMoveId, String originalMoveId, double originalPower, String type) {
        String displayed = normalize(displayedMoveId);
        if (!isMaxMove(displayed)) return null;
        if (displayed.equals("maxguard")) return 0;
        if (displayed.equals("gmaxdrumsolo") || displayed.equals("gmaxfireball")
            || displayed.equals("gmaxhydrosnipe")) return 160;
        String original = normalize(originalMoveId);
        if (original.isEmpty() || isMaxMove(original) || original.equals("struggle")
            || !Double.isFinite(originalPower) || originalPower < 0) return null;
        Integer override = OVERRIDES.get(original);
        if (override != null) return override;
        if (originalPower == 0) return 100;
        if (type == null) return null;
        boolean reduced = type.equalsIgnoreCase("fighting") || type.equalsIgnoreCase("poison");
        if (originalPower >= 150) return reduced ? 100 : 150;
        if (originalPower >= 110) return reduced ? 95 : 140;
        if (originalPower >= 75) return reduced ? 90 : 130;
        if (originalPower >= 65) return reduced ? 85 : 120;
        if (originalPower >= 55) return reduced ? 80 : 110;
        if (originalPower >= 45) return reduced ? 75 : 100;
        return reduced ? 70 : 90;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
