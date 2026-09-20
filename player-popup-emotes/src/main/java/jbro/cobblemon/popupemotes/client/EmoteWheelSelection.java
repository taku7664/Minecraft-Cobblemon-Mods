package jbro.cobblemon.popupemotes.client;

final class EmoteWheelSelection {
    private static final double CENTER_RADIUS = 28.0;
    private static final double OUTER_RADIUS = 92.0;

    private EmoteWheelSelection() {
    }

    static int index(double dx, double dy, int slotCount) {
        if (slotCount <= 0) {
            return -1;
        }
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= CENTER_RADIUS) {
            return slotCount - 1;
        }
        int outerSlotCount = slotCount - 1;
        if (outerSlotCount <= 0 || distance > OUTER_RADIUS) {
            return -1;
        }
        double sliceAngle = Math.PI * 2.0 / outerSlotCount;
        double normalized = Math.atan2(dy, dx) + Math.PI / 2.0 + sliceAngle / 2.0;
        if (normalized < 0.0) {
            normalized += Math.PI * 2.0;
        }
        return (int)(normalized / sliceAngle) % outerSlotCount;
    }
}
