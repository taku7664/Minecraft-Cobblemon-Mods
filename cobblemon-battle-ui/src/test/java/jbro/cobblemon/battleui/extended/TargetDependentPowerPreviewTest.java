package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TargetDependentPowerPreviewTest {
    @Test
    void hexUsesTheOnlyActiveTargetsCondition() {
        assertEquals(
            new TargetDependentPowerPreview.Range(130, 130),
            TargetDependentPowerPreview.hex(65, List.of(true))
        );
        assertEquals(
            new TargetDependentPowerPreview.Range(65, 65),
            TargetDependentPowerPreview.hex(65, List.of(false))
        );
    }

    @Test
    void hexReportsARangeWhenActiveTargetsDiffer() {
        assertEquals(
            new TargetDependentPowerPreview.Range(65, 130),
            TargetDependentPowerPreview.hex(65, List.of(false, true))
        );
    }

    @Test
    void hexDoesNotGuessWithoutAnActiveTarget() {
        assertNull(TargetDependentPowerPreview.hex(65, List.of()));
    }
}
