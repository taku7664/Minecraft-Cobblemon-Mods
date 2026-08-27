package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ReturnDimensionPolicyTest {
    @Test
    void acceptsEveryDimensionExceptTheRoomDimension() {
        assertTrue(ReturnDimensionPolicy.shouldSave(ResourceLocation.parse("minecraft:overworld")));
        assertTrue(ReturnDimensionPolicy.shouldSave(ResourceLocation.parse("modded:moon")));
        assertFalse(ReturnDimensionPolicy.shouldSave(RoomDimensions.ID));
    }
}
