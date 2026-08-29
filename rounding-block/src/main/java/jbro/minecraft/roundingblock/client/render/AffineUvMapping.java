package jbro.minecraft.roundingblock.client.render;

record AffineUvMapping(double u0, double uA, double uB, double v0, double vA, double vB) {
    private static final double EPSILON = 1.0e-8;

    static AffineUvMapping fit(double[] a, double[] b, float[] u, float[] v) {
        if (a.length != 4 || b.length != 4 || u.length != 4 || v.length != 4) {
            throw new IllegalArgumentException("Exactly four UV samples are required");
        }
        for (int first = 0; first < 2; first++) {
            for (int second = first + 1; second < 3; second++) {
                for (int third = second + 1; third < 4; third++) {
                    AffineUvMapping mapping = fitThree(a, b, u, v, first, second, third);
                    if (mapping != null && mapping.matches(a, b, u, v)) {
                        return mapping;
                    }
                }
            }
        }
        throw new IllegalArgumentException("Quad UVs are not an affine mapping");
    }

    private static AffineUvMapping fitThree(
        double[] a,
        double[] b,
        float[] u,
        float[] v,
        int i0,
        int i1,
        int i2
    ) {
        double da1 = a[i1] - a[i0];
        double db1 = b[i1] - b[i0];
        double da2 = a[i2] - a[i0];
        double db2 = b[i2] - b[i0];
        double determinant = da1 * db2 - da2 * db1;
        if (Math.abs(determinant) <= EPSILON) {
            return null;
        }
        double uA = ((u[i1] - u[i0]) * db2 - (u[i2] - u[i0]) * db1) / determinant;
        double uB = (da1 * (u[i2] - u[i0]) - da2 * (u[i1] - u[i0])) / determinant;
        double vA = ((v[i1] - v[i0]) * db2 - (v[i2] - v[i0]) * db1) / determinant;
        double vB = (da1 * (v[i2] - v[i0]) - da2 * (v[i1] - v[i0])) / determinant;
        return new AffineUvMapping(
            u[i0] - uA * a[i0] - uB * b[i0], uA, uB,
            v[i0] - vA * a[i0] - vB * b[i0], vA, vB
        );
    }

    Uv map(double a, double b) {
        return new Uv((float) (u0 + uA * a + uB * b), (float) (v0 + vA * a + vB * b));
    }

    private boolean matches(double[] a, double[] b, float[] u, float[] v) {
        for (int i = 0; i < 4; i++) {
            Uv mapped = map(a[i], b[i]);
            if (Math.abs(mapped.u() - u[i]) > 1.0e-5 || Math.abs(mapped.v() - v[i]) > 1.0e-5) {
                return false;
            }
        }
        return true;
    }

    record Uv(float u, float v) {
    }
}
