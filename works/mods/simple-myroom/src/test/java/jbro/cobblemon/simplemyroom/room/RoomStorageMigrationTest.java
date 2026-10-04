package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class RoomStorageMigrationTest {
    @Test
    void dropsLegacyAllowedDimensionListOnNextSave() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("layout_size", 100);
        legacy.putInt("layout_spacing", 512);
        legacy.putInt("layout_grid_width", 1024);
        legacy.putInt("layout_floor_y", 64);
        ListTag dimensions = new ListTag();
        dimensions.add(StringTag.valueOf("minecraft:overworld"));
        legacy.put("allowed_return_dimensions", dimensions);

        RoomStorage storage = RoomStorage.load(legacy, null);
        CompoundTag saved = storage.save(new CompoundTag(), null);

        assertTrue(storage.isDirty());
        assertFalse(saved.contains("allowed_return_dimensions"));
    }
}
