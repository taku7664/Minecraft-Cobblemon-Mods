package jbro.minecraft.roundingblock.mesh;

public enum CubeFace {
    WEST(0, -1),
    EAST(0, 1),
    DOWN(1, -1),
    UP(1, 1),
    NORTH(2, -1),
    SOUTH(2, 1);

    private final int axis;
    private final int sign;

    CubeFace(int axis, int sign) {
        this.axis = axis;
        this.sign = sign;
    }

    public int axis() {
        return axis;
    }

    public int sign() {
        return sign;
    }

    public Vec3 normal() {
        return Vec3.ZERO.withComponent(axis, sign);
    }

    public static CubeFace of(int axis, int sign) {
        for (CubeFace face : values()) {
            if (face.axis == axis && face.sign == Integer.signum(sign)) {
                return face;
            }
        }
        throw new IllegalArgumentException("Unknown face axis=" + axis + ", sign=" + sign);
    }
}
