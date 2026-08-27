package jbro.cobblemon.simplemyroom.room;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class RoomDimensions {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("myroom", "rooms");
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ID);

    private RoomDimensions() {
    }

    public static boolean isRoom(Level level) {
        return level.dimension().equals(KEY);
    }
}
