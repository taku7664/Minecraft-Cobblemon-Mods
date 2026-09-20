package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Restores only the shaved face corners of blocks that touch diagonally.
 *
 * <p>The patches stay inside the center block and meet at the original cube
 * edge or vertex. They deliberately do not place a cylinder or sphere in the
 * surrounding empty cells.</p>
 */
final class DiagonalContactPatchMesher {
    private static final double HALF_EXTENT = 0.5;
    private final double radius;

    DiagonalContactPatchMesher(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0 || radius >= HALF_EXTENT) {
            throw new IllegalArgumentException("radius must be finite and between zero and half a block");
        }
        this.radius = radius;
    }

    MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int cornerX = 0; cornerX <= 1; cornerX++) {
            for (int cornerY = 0; cornerY <= 1; cornerY++) {
                for (int cornerZ = 0; cornerZ <= 1; cornerZ++) {
                    int mask = vertexMask(neighborhood, cornerX, cornerY, cornerZ);
                    if (!isFaceDisconnected(mask)) {
                        continue;
                    }
                    int currentOctant = (1 - cornerX) | ((1 - cornerY) << 1) | ((1 - cornerZ) << 2);
                    emitCornerPatches(cornerX, cornerY, cornerZ, currentOctant, mask, output);
                }
            }
        }
        return new MeshPlan(output);
    }

    private void emitCornerPatches(
        int cornerX,
        int cornerY,
        int cornerZ,
        int currentOctant,
        int mask,
        List<MeshPrimitive> output
    ) {
        Vec3 corner = new Vec3(cornerX, cornerY, cornerZ);
        for (int faceAxis = 0; faceAxis < 3; faceAxis++) {
            if (occupied(mask, currentOctant ^ (1 << faceAxis))) {
                continue;
            }
            int firstAxis = (faceAxis + 1) % 3;
            int secondAxis = (faceAxis + 2) % 3;
            double firstDistance = tangentDistance(mask, currentOctant, firstAxis);
            double secondDistance = tangentDistance(mask, currentOctant, secondAxis);
            double firstDirection = inwardDirection(currentOctant, firstAxis);
            double secondDirection = inwardDirection(currentOctant, secondAxis);
            Vec3 first = corner.withComponent(
                firstAxis,
                corner.component(firstAxis) + firstDirection * firstDistance
            );
            Vec3 second = corner.withComponent(
                secondAxis,
                corner.component(secondAxis) + secondDirection * secondDistance
            );
            Vec3 opposite = first.withComponent(secondAxis, second.component(secondAxis));
            int outwardSign = inwardDirection(currentOctant, faceAxis) > 0.0 ? -1 : 1;
            CubeFace face = CubeFace.of(faceAxis, outwardSign);
            addOrientedPatch(output, face, List.of(corner, first, opposite, second));
        }
    }

    private double tangentDistance(int mask, int currentOctant, int axis) {
        int disconnected = mask & ~faceConnectedComponent(mask, currentOctant);
        int currentSide = (currentOctant >> axis) & 1;
        for (int octant = 0; octant < 8; octant++) {
            if (occupied(disconnected, octant) && ((octant >> axis) & 1) == currentSide) {
                return HALF_EXTENT;
            }
        }
        return radius;
    }

    private static double inwardDirection(int octant, int axis) {
        return ((octant >> axis) & 1) == 0 ? -1.0 : 1.0;
    }

    private static void addOrientedPatch(
        List<MeshPrimitive> output,
        CubeFace face,
        List<Vec3> positions
    ) {
        List<MeshVertex> vertices = new ArrayList<>(positions.size());
        for (Vec3 position : positions) {
            vertices.add(new MeshVertex(position, face.normal()));
        }
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        if (geometricNormal.dot(face.normal()) < 0.0) {
            Collections.reverse(vertices);
        }
        output.add(new MeshPrimitive(PrimitiveKind.CONTACT, face, vertices));
    }

    private static int vertexMask(
        VoxelNeighborhood neighborhood,
        int cornerX,
        int cornerY,
        int cornerZ
    ) {
        int mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int x = cornerX + (octant & 1) - 1;
            int y = cornerY + ((octant >> 1) & 1) - 1;
            int z = cornerZ + ((octant >> 2) & 1) - 1;
            if (neighborhood.occupied(x, y, z)) {
                mask |= 1 << octant;
            }
        }
        return mask;
    }

    static boolean isFaceDisconnected(int mask) {
        if (Integer.bitCount(mask) < 2) {
            return false;
        }
        int firstOctant = Integer.numberOfTrailingZeros(mask);
        return faceConnectedComponent(mask, firstOctant) != mask;
    }

    private static int faceConnectedComponent(int mask, int startOctant) {
        int visited = 1 << startOctant;
        int frontier = visited;
        while (frontier != 0) {
            int bit = Integer.lowestOneBit(frontier);
            frontier &= ~bit;
            int octant = Integer.numberOfTrailingZeros(bit);
            for (int axis = 0; axis < 3; axis++) {
                int neighborBit = 1 << (octant ^ (1 << axis));
                if ((mask & neighborBit) != 0 && (visited & neighborBit) == 0) {
                    visited |= neighborBit;
                    frontier |= neighborBit;
                }
            }
        }
        return visited;
    }

    private static boolean occupied(int mask, int octant) {
        return (mask & (1 << octant)) != 0;
    }
}
