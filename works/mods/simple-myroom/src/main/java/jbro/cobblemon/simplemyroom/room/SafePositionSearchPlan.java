package jbro.cobblemon.simplemyroom.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SafePositionSearchPlan {
    private SafePositionSearchPlan() {
    }

    public static List<Offset> offsets(int horizontalRadius, int verticalRange) {
        if (horizontalRadius < 0 || verticalRange < 0) {
            throw new IllegalArgumentException("Safe position search ranges must not be negative.");
        }
        List<Offset> offsets = new ArrayList<>();
        for (int y = -verticalRange; y <= verticalRange; y++) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                    offsets.add(new Offset(x, y, z));
                }
            }
        }
        offsets.sort(Comparator
            .comparingInt((Offset offset) -> Math.max(Math.abs(offset.x), Math.abs(offset.z)))
            .thenComparingInt(offset -> Math.abs(offset.y))
            .thenComparingInt(offset -> Math.abs(offset.x) + Math.abs(offset.z))
            .thenComparingInt(Offset::y)
            .thenComparingInt(Offset::x)
            .thenComparingInt(Offset::z));
        return List.copyOf(offsets);
    }

    public record Offset(int x, int y, int z) {
    }
}
