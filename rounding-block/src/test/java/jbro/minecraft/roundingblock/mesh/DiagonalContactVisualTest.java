package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DiagonalContactVisualTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final double SCALE = 240.0;

    @Test
    void rendersCanonicalDiagonalEdgeContactForVisualRegression() throws IOException {
        Set<Cell> solids = Set.of(new Cell(0, 0, 0), new Cell(1, 0, 1));
        Path output = Path.of("build", "diagnostics", "diagonal-edge-contact.png");
        BufferedImage image = render(solids, output);
        assertTrue(Files.size(output) > 0L);
        int background = new Color(255, 0, 255).getRGB();
        for (int y = 330; y <= 525; y++) {
            int testedY = y;
            assertTrue(
                image.getRGB(399, y) != background
                    && image.getRGB(400, y) != background
                    && image.getRGB(401, y) != background,
                () -> "background remains visible through diagonal seam at y=" + testedY
            );
        }
        assertFilledSquare(image, 400, 430, 10, background, "edge contact must be a rounded neck, not a line");
        assertTrue(image.getRGB(400, 315) == background, "edge contact protrudes above the rounded silhouette");
        assertTrue(image.getRGB(400, 550) == background, "edge contact protrudes below the rounded silhouette");
    }

    @Test
    void rendersCanonicalDiagonalVertexContactForVisualRegression() throws IOException {
        Path output = Path.of("build", "diagnostics", "diagonal-vertex-contact.png");
        BufferedImage image = render(Set.of(new Cell(0, 0, 0), new Cell(1, 1, 1)), output);
        assertTrue(Files.size(output) > 0L);
        int background = new Color(255, 0, 255).getRGB();
        assertTrue(image.getRGB(400, 312) != background);
        assertFilledSquare(image, 400, 312, 3, background, "vertex contact is thinner than a rounded point");
        int closedRows = 0;
        for (int y = 302; y <= 322; y++) {
            boolean hasBackground = false;
            for (int x = 390; x <= 410; x++) {
                if (image.getRGB(x, y) == background) {
                    hasBackground = true;
                }
            }
            if (!hasBackground) {
                closedRows++;
            }
        }
        int measuredClosedRows = closedRows;
        assertTrue(
            closedRows <= 1,
            () -> "vertex contact must stay point-like instead of becoming a rod, got " + measuredClosedRows
        );
    }

    @Test
    void rendersReferenceLikeThreeBlockJunction() throws IOException {
        Path output = Path.of("build", "diagnostics", "diagonal-three-block-junction.png");
        BufferedImage image = render(Set.of(
            new Cell(0, 0, 0),
            new Cell(0, 1, 1),
            new Cell(1, 0, 1)
        ), output);
        assertTrue(Files.size(output) > 0L);
        int background = new Color(255, 0, 255).getRGB();
        assertFilledSquare(
            image, 400, 318, 10, background,
            "background remains visible through the three-block rounded junction"
        );
    }

    private static void assertFilledSquare(
        BufferedImage image,
        int centerX,
        int centerY,
        int radius,
        int background,
        String message
    ) {
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                assertTrue(image.getRGB(x, y) != background, message + " at " + x + "," + y);
            }
        }
    }

    private static BufferedImage render(Set<Cell> solids, Path output) throws IOException {
        List<DrawTriangle> triangles = new ArrayList<>();
        RoundedVoxelMesher mesher = new RoundedVoxelMesher();
        for (Cell owner : solids) {
            MeshPlan plan = mesher.mesh(neighborhood(owner, solids));
            Color color = new Color(116, 160, 52);
            for (MeshPrimitive primitive : plan.primitives()) {
                addTriangle(triangles, owner, color, primitive, 0, 1, 2);
                if (primitive.vertices().size() == 4) {
                    addTriangle(triangles, owner, color, primitive, 0, 2, 3);
                }
            }
        }
        triangles.sort(Comparator.comparingDouble(DrawTriangle::depth));

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(255, 0, 255));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            for (DrawTriangle triangle : triangles) {
                graphics.setColor(triangle.color());
                graphics.fillPolygon(triangle.polygon());
            }
        } finally {
            graphics.dispose();
        }

        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        return image;
    }

    private static void addTriangle(
        List<DrawTriangle> output,
        Cell owner,
        Color base,
        MeshPrimitive primitive,
        int first,
        int second,
        int third
    ) {
        MeshVertex a = world(primitive.vertices().get(first), owner);
        MeshVertex b = world(primitive.vertices().get(second), owner);
        MeshVertex c = world(primitive.vertices().get(third), owner);
        Polygon polygon = new Polygon();
        for (MeshVertex vertex : List.of(a, b, c)) {
            polygon.addPoint(screenX(vertex.position()), screenY(vertex.position()));
        }
        Vec3 averageNormal = a.normal().add(b.normal()).add(c.normal()).normalize();
        double light = 0.52 + 0.48 * Math.max(0.0, averageNormal.dot(new Vec3(-0.4, 0.8, -0.4).normalize()));
        Color shaded = new Color(
            (int) Math.round(base.getRed() * light),
            (int) Math.round(base.getGreen() * light),
            (int) Math.round(base.getBlue() * light)
        );
        double depth = (a.position().x() - a.position().z() + 0.35 * a.position().y()
            + b.position().x() - b.position().z() + 0.35 * b.position().y()
            + c.position().x() - c.position().z() + 0.35 * c.position().y()) / 3.0;
        output.add(new DrawTriangle(depth, polygon, shaded));
    }

    private static MeshVertex world(MeshVertex vertex, Cell owner) {
        return new MeshVertex(
            vertex.position().add(new Vec3(owner.x(), owner.y(), owner.z())),
            vertex.normal()
        );
    }

    private static int screenX(Vec3 point) {
        return (int) Math.round(WIDTH * 0.5 + (point.x() + point.z() - 2.0) * SCALE * 0.72);
    }

    private static int screenY(Vec3 point) {
        return (int) Math.round(HEIGHT * 0.72 - (point.y() - 0.5) * SCALE
            + (point.x() - point.z()) * SCALE * 0.24);
    }

    private static VoxelNeighborhood neighborhood(Cell owner, Set<Cell> solids) {
        VoxelNeighborhood.Builder builder = VoxelNeighborhood.builder();
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (solids.contains(new Cell(owner.x() + x, owner.y() + y, owner.z() + z))) {
                        builder.occupy(x, y, z);
                    }
                }
            }
        }
        return builder.build();
    }

    private record Cell(int x, int y, int z) {
    }

    private record DrawTriangle(double depth, Polygon polygon, Color color) {
    }
}
