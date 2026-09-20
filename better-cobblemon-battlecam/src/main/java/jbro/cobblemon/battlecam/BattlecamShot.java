package jbro.cobblemon.battlecam;

public record BattlecamShot(BattlecamPoint camera, BattlecamPoint target, String name) {
    public boolean isFinite() {
        return camera.isFinite() && target.isFinite();
    }
}
