package com.batmite2b.battlecam.client;

import net.minecraft.world.phys.Vec3;

public record CameraPose(Vec3 pos, float yaw, float pitch, float fov) {
}
