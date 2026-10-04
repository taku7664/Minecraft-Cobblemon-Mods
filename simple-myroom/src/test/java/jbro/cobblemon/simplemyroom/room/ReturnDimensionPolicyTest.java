package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ReturnDimensionPolicyTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation PLAZA = ResourceLocation.parse("jbro_policy:plaza");
    private static final List<String> HUBS = List.of("jbro_policy:plaza");

    @Test
    void acceptsEveryDimensionExceptTheRoomDimension() {
        assertTrue(ReturnDimensionPolicy.shouldSave(OVERWORLD));
        assertTrue(ReturnDimensionPolicy.shouldSave(ResourceLocation.parse("modded:moon")));
        assertFalse(ReturnDimensionPolicy.shouldSave(RoomDimensions.ID));
    }

    @Test
    void enteringFromOutsideAlwaysSavesANewPoint() {
        assertTrue(ReturnDimensionPolicy.shouldSaveOnEntry(OVERWORLD, false, HUBS));
        assertTrue(ReturnDimensionPolicy.shouldSaveOnEntry(OVERWORLD, true, HUBS));
    }

    @Test
    void enteringFromAnotherHubKeepsThePointOfTheTrip() {
        assertFalse(ReturnDimensionPolicy.shouldSaveOnEntry(PLAZA, true, HUBS));
        assertTrue(ReturnDimensionPolicy.shouldSaveOnEntry(PLAZA, false, HUBS));
        assertFalse(ReturnDimensionPolicy.shouldSaveOnEntry(RoomDimensions.ID, false, HUBS));
    }

    @Test
    void onlyDimensionsOutsideEveryHubEndTheTrip() {
        assertTrue(ReturnDimensionPolicy.isStale(OVERWORLD, HUBS));
        assertFalse(ReturnDimensionPolicy.isStale(PLAZA, HUBS));
        assertFalse(ReturnDimensionPolicy.isStale(RoomDimensions.ID, HUBS));
        assertTrue(ReturnDimensionPolicy.isStale(PLAZA, List.of()));
    }
}
