package jbro.minecraft.roundingblock.mesh;

/** Supported axis-aligned block-height profiles. */
public enum VerticalBlockShape {
    FULL(0.0, 1.0, 0b11),
    BOTTOM_HALF(0.0, 0.5, 0b01),
    TOP_HALF(0.5, 1.0, 0b10);

    private final double minimumY;
    private final double maximumY;
    private final int layerBits;

    VerticalBlockShape(double minimumY, double maximumY, int layerBits) {
        this.minimumY = minimumY;
        this.maximumY = maximumY;
        this.layerBits = layerBits;
    }

    public double minimum(int axis) {
        return axis == 1 ? minimumY : 0.0;
    }

    public double maximum(int axis) {
        return axis == 1 ? maximumY : 1.0;
    }

    public boolean occupiesLayer(int layer) {
        if (layer < 0 || layer > 1) {
            throw new IllegalArgumentException("Vertical layer must be 0 or 1: " + layer);
        }
        return (layerBits & (1 << layer)) != 0;
    }

    public boolean isPartial() {
        return this != FULL;
    }
}
