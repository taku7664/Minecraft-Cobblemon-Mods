package jbro.cobblemon.simplemyroom.room;

public final class PhysicalEntryPolicy {
    private PhysicalEntryPolicy() {
    }

    public static boolean shouldEject(boolean enabled, boolean visitAdmin, boolean canOccupy) {
        return enabled && !visitAdmin && !canOccupy;
    }
}
