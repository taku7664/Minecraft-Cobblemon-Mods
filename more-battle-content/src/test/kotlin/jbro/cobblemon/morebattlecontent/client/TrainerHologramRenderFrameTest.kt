package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrainerHologramRenderFrameTest {
    @Test
    fun `captured pose remains unchanged when the source stack is later unwound or reused`() {
        val source = PoseStack().apply { translate(3.0, 4.0, 5.0) }
        val frame = TrainerHologramRenderFrame.capture(source, Vec3(10.0, 20.0, 30.0), 0.25F)

        source.translate(100.0, 200.0, 300.0)

        val capturedOrigin = frame.newPoseStack().last().pose().transformPosition(Vector3f())
        assertEquals(3F, capturedOrigin.x, 0.0001F)
        assertEquals(4F, capturedOrigin.y, 0.0001F)
        assertEquals(5F, capturedOrigin.z, 0.0001F)
        assertEquals(0.25F, frame.partialTick)
    }

    @Test
    fun `world position is converted to camera relative position exactly once`() {
        val frame = TrainerHologramRenderFrame.capture(PoseStack(), Vec3(10.0, 20.0, 30.0), 0F)

        val relative = frame.relativePosition(Vec3(13.5, 24.0, 27.0))

        assertEquals(Vec3(3.5, 4.0, -3.0), relative)
    }

    @Test
    fun `terrain frame keeps an independent inverse view projection matrix`() {
        val projection = Matrix4f().perspective(1.1F, 1.6F, 0.05F, 256F)
        val position = Matrix4f().rotateY(0.7F).translate(-3F, -4F, -5F)
        val expected = Matrix4f(projection).mul(position).invert()
        val frame = TerrainHologramRenderFrame.capture(projection, position, Vec3(7.0, 8.0, 9.0))

        projection.identity()
        position.identity()

        val actual = frame.inverseViewProjection()
        for (column in 0..3) {
            for (row in 0..3) {
                assertEquals(expected[column, row], actual[column, row], 0.0001F)
            }
        }
        assertEquals(Vec3(7.0, 8.0, 9.0), frame.cameraPosition)
    }
}
