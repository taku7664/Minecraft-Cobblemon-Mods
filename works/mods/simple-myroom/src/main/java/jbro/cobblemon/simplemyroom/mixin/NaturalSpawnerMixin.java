package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void simpleMyRoom$stopNaturalSpawning(
        ServerLevel level,
        LevelChunk chunk,
        NaturalSpawner.SpawnState state,
        boolean spawnFriendlies,
        boolean spawnEnemies,
        boolean rareSpawn,
        CallbackInfo callback
    ) {
        if (SimpleMyRoom.protectionEnabled()
            && SimpleMyRoom.config().protection.preventNaturalMobSpawning
            && RoomDimensions.isRoom(level)) callback.cancel();
    }
}
