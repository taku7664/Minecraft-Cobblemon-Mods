package jbro.cobblemon.simplemyroom.room;

import java.util.Collection;
import net.minecraft.resources.ResourceLocation;

/**
 * Which return point a room exit uses when players hop between rooms and other hubs such as a server plaza.
 *
 * <p>The point is where the player stood before this trip through the hubs began: entering from outside saves it,
 * entering from another hub keeps the one already saved, and being anywhere outside every hub clears it. Each hub
 * mod keeps its own point by the same rule, so two hubs never send a player back and forth between each other.
 */
public final class ReturnDimensionPolicy {
    private ReturnDimensionPolicy() {
    }

    public static boolean shouldSave(ResourceLocation dimension) {
        return dimension != null && !RoomDimensions.ID.equals(dimension);
    }

    /** Whether entering a room from {@code source} replaces the return point. */
    public static boolean shouldSaveOnEntry(ResourceLocation source, boolean hasPoint, Collection<String> otherHubs) {
        if (!shouldSave(source)) return false;
        return !hasPoint || !isOtherHub(source, otherHubs);
    }

    /** A player seen in {@code current} has left every hub, so the saved point belongs to a finished trip. */
    public static boolean isStale(ResourceLocation current, Collection<String> otherHubs) {
        return shouldSave(current) && !isOtherHub(current, otherHubs);
    }

    private static boolean isOtherHub(ResourceLocation dimension, Collection<String> otherHubs) {
        return otherHubs.contains(dimension.toString());
    }
}
