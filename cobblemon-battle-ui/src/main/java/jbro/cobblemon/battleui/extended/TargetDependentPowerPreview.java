package jbro.cobblemon.battleui.extended;

import java.util.List;

/** Computes honest power bounds when a move's power depends on the eventual target. */
public final class TargetDependentPowerPreview {
    private TargetDependentPowerPreview() {}

    public record Range(int minimum, int maximum) {
        public Range {
            if (minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("Invalid power range");
            }
        }
    }

    /**
     * Hex doubles against a statused target. Before target selection, mixed
     * target states must remain a range instead of borrowing one target's value.
     */
    public static Range hex(int basePower, List<Boolean> boostedTargets) {
        if (boostedTargets.isEmpty()) {
            return null;
        }

        boolean anyBoosted = boostedTargets.stream().anyMatch(Boolean.TRUE::equals);
        boolean allBoosted = boostedTargets.stream().allMatch(Boolean.TRUE::equals);
        return new Range(allBoosted ? basePower * 2 : basePower, anyBoosted ? basePower * 2 : basePower);
    }
}
