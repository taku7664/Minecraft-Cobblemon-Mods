package jbro.cobblemon.battlecam;

import java.util.List;

public final class BattlecamShotPlanner {
    private BattlecamShotPlanner() {
    }

    public static List<BattlecamShot> plan(List<BattlecamPoint> side1, List<BattlecamPoint> side2) {
        if (side1.isEmpty() || side2.isEmpty()) {
            return List.of();
        }

        BattlecamPoint firstCenter = average(side1);
        BattlecamPoint secondCenter = average(side2);
        BattlecamPoint target = average(List.of(firstCenter, secondCenter)).add(new BattlecamPoint(0, 0.55, 0));
        BattlecamPoint axis = secondCenter.subtract(firstCenter)
            .horizontalUnitOr(new BattlecamPoint(1, 0, 0));
        BattlecamPoint perpendicular = new BattlecamPoint(-axis.z(), 0, axis.x());
        double span = maximumDistance(target, side1, side2);
        double distance = clamp(span * 1.55 + 5.5, 8.5, 20.0);

        return List.of(
            shot(target.add(perpendicular.multiply(distance)).add(new BattlecamPoint(0, 3.0, 0)), target, "SIDE A"),
            shot(target.subtract(perpendicular.multiply(distance)).add(new BattlecamPoint(0, 3.0, 0)), target, "SIDE B"),
            shot(target.subtract(axis.multiply(distance)).add(new BattlecamPoint(0, 3.8, 0)), target, "END A"),
            shot(target.add(axis.multiply(distance)).add(new BattlecamPoint(0, 3.8, 0)), target, "END B"),
            shot(target.add(perpendicular.multiply(distance * 0.72)).add(axis.multiply(distance * 0.28))
                .add(new BattlecamPoint(0, 7.5, 0)), target, "HIGH WIDE"),
            shot(target.subtract(perpendicular.multiply(distance * 0.58)).subtract(axis.multiply(distance * 0.18))
                .add(new BattlecamPoint(0, 1.8, 0)), target, "LOW CLOSE")
        );
    }

    private static BattlecamShot shot(BattlecamPoint camera, BattlecamPoint target, String name) {
        return new BattlecamShot(camera, target, name);
    }

    private static BattlecamPoint average(List<BattlecamPoint> points) {
        BattlecamPoint sum = new BattlecamPoint(0, 0, 0);
        for (BattlecamPoint point : points) {
            sum = sum.add(point);
        }
        return sum.multiply(1.0 / points.size());
    }

    @SafeVarargs
    private static double maximumDistance(BattlecamPoint center, List<BattlecamPoint>... groups) {
        double maximum = 0.0;
        for (List<BattlecamPoint> group : groups) {
            for (BattlecamPoint point : group) {
                BattlecamPoint delta = point.subtract(center);
                maximum = Math.max(maximum, Math.sqrt(delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z()));
            }
        }
        return maximum;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
