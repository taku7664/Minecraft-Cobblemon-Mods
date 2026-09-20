package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BattlecamShotPlannerTest {
    @Test
    void framesSinglesAndDoublesWithSixFiniteShots() {
        var singles = BattlecamShotPlanner.plan(
            List.of(new BattlecamPoint(-3, 1, 0)),
            List.of(new BattlecamPoint(3, 1, 0))
        );
        var doubles = BattlecamShotPlanner.plan(
            List.of(new BattlecamPoint(-3, 1, -2), new BattlecamPoint(-3, 1, 2)),
            List.of(new BattlecamPoint(3, 1, -2), new BattlecamPoint(3, 1, 2))
        );

        assertEquals(6, singles.size());
        assertEquals(6, doubles.size());
        assertTrue(doubles.stream().allMatch(BattlecamShot::isFinite));
    }

    @Test
    void rejectsAnIncompleteBattleFrame() {
        assertTrue(BattlecamShotPlanner.plan(List.of(), List.of(new BattlecamPoint(3, 1, 0))).isEmpty());
    }
}
