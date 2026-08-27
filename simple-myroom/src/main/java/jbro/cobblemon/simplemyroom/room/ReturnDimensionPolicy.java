package jbro.cobblemon.simplemyroom.room;

import net.minecraft.resources.ResourceLocation;

public final class ReturnDimensionPolicy {
    private ReturnDimensionPolicy() {
    }

    public static boolean shouldSave(ResourceLocation dimension) {
        return dimension != null && !RoomDimensions.ID.equals(dimension);
    }
}
