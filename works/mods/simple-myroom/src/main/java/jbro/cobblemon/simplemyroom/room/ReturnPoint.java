package jbro.cobblemon.simplemyroom.room;

public record ReturnPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
    public ReturnPoint {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("Return dimension must not be blank.");
        }
    }
}
