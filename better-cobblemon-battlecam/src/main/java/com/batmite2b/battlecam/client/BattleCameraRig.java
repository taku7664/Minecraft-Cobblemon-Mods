/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.util.math.Vec3d
 */
package com.batmite2b.battlecam.client;

import net.minecraft.util.math.Vec3d;

public final class BattleCameraRig {
    private Vec3d currentPos = Vec3d.ZERO;
    private float currentYaw = 0.0f;
    private float currentPitch = 0.0f;
    private float currentFov = 70.0f;
    private Vec3d targetPos = Vec3d.ZERO;
    private float targetYaw = 0.0f;
    private float targetPitch = 0.0f;
    private float targetFov = 70.0f;
    public boolean active = false;
    private boolean initialized = false;

    public void reset() {
        this.currentPos = Vec3d.ZERO;
        this.currentYaw = 0.0f;
        this.currentPitch = 0.0f;
        this.currentFov = 70.0f;
        this.targetPos = Vec3d.ZERO;
        this.targetYaw = 0.0f;
        this.targetPitch = 0.0f;
        this.targetFov = 70.0f;
        this.active = false;
        this.initialized = false;
    }

    public void setDesired(Vec3d pos, float yaw, float pitch, float fov) {
        this.setDesired(pos, yaw, pitch, fov, false);
    }

    public void setDesired(Vec3d pos, float yaw, float pitch, float fov, boolean cut) {
        this.targetPos = pos;
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        this.targetFov = fov;
        this.active = true;
        if (!this.initialized || cut) {
            this.currentPos = pos;
            this.currentYaw = yaw;
            this.currentPitch = pitch;
            this.currentFov = fov;
            this.initialized = true;
        }
    }

    public void renderStep() {
        if (!this.active || !this.initialized) {
            return;
        }
        double posAlpha = 0.12;
        float angleAlpha = 0.16f;
        float fovAlpha = 0.12f;
        this.currentPos = this.currentPos.lerp(this.targetPos, posAlpha);
        this.currentYaw = BattleCameraRig.lerpAngle(this.currentYaw, this.targetYaw, angleAlpha);
        this.currentPitch = BattleCameraRig.lerpAngle(this.currentPitch, this.targetPitch, angleAlpha);
        this.currentFov = BattleCameraRig.lerp(this.currentFov, this.targetFov, fovAlpha);
    }

    public Vec3d getRenderPos() {
        return this.currentPos;
    }

    public float getRenderYaw() {
        return this.currentYaw;
    }

    public float getRenderPitch() {
        return this.currentPitch;
    }

    public float getRenderFov() {
        return this.currentFov;
    }

    private static float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    private static float lerpAngle(float from, float to, float alpha) {
        float delta = BattleCameraRig.wrapDegrees(to - from);
        return from + delta * alpha;
    }

    private static float wrapDegrees(float value) {
        if ((value %= 360.0f) >= 180.0f) {
            value -= 360.0f;
        }
        if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }
}
