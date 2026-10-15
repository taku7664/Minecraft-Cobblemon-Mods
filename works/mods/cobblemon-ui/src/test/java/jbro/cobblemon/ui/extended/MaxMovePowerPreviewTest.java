package jbro.cobblemon.ui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class MaxMovePowerPreviewTest {
    @Test
    void matchesActualBundledShowdownForEveryDamagingBaseMove() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream(
            "/max-move-power-cobblemon-1.8.1.json"), StandardCharsets.UTF_8)) {
            var cases = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases");
            assertEquals(595, cases.size());
            for (var element : cases) {
                var row = element.getAsJsonArray();
                assertEquals(row.get(3).getAsInt(), MaxMovePowerPreview.power("maxstrike",
                    row.get(0).getAsString(), row.get(1).getAsDouble(), row.get(2).getAsString()),
                    row.get(0).getAsString());
            }
        }
    }

    @Test
    void airstreamUsesTheOriginalMoveAndItsExplicitOverrides() {
        assertEquals(130, MaxMovePowerPreview.power("Max Airstream", "airslash", 75, "Flying"));
        assertEquals(90, MaxMovePowerPreview.power("maxairstream", "gust", 40, "Flying"));
        assertEquals(130, MaxMovePowerPreview.power("maxairstream", "dualwingbeat", 40, "Flying"));
        assertEquals(95, MaxMovePowerPreview.power("maxknuckle", "closecombat", 120, "Fighting"));
    }

    @Test
    void guardAndTheThreeFixedPowerGmaxMovesDoNotUseTheConversionTable() {
        assertEquals(0, MaxMovePowerPreview.power("maxguard", "protect", 0, "Normal"));
        for (String move : new String[]{"gmaxdrumsolo", "gmaxfireball", "gmaxhydrosnipe"}) {
            assertEquals(160, MaxMovePowerPreview.power(move, "tackle", 40, "Normal"));
        }
        assertEquals(130, MaxMovePowerPreview.power("gmaxwildfire", "flamethrower", 90, "Fire"));
    }

    @Test
    void missingOriginalNeverFallsBackToTheDisplayedPlaceholder() {
        assertNull(MaxMovePowerPreview.power("maxairstream", null, 10, "Flying"));
        assertNull(MaxMovePowerPreview.power("maxairstream", "maxairstream", 10, "Flying"));
        assertNull(MaxMovePowerPreview.power("maxairstream", "airslash", -1, "Flying"));
        assertNull(MaxMovePowerPreview.power("airslash", "airslash", 75, "Flying"));
    }
}
