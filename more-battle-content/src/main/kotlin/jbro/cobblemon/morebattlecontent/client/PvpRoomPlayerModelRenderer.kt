package jbro.cobblemon.morebattlecontent.client

import com.mojang.authlib.GameProfile
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.RemotePlayer
import org.joml.Quaternionf
import org.joml.Vector3f

internal object PvpRoomPlayerModelSpec {
    const val BODY_YAW = 180f
    const val HEAD_YAW = 180f
    const val PITCH = 0f
    const val FOLLOWS_MOUSE = false
    const val ADVANCES_IDLE_ANIMATION = true
}

internal class PvpRoomPlayerModelRenderer {
    private val cachedPlayers = mutableMapOf<UUID, RemotePlayer>()

    fun retain(playerIds: Set<UUID>) {
        cachedPlayers.keys.retainAll(playerIds)
    }

    fun render(graphics: GuiGraphics, playerId: UUID, profile: GameProfile, x: Int, centerY: Int, scale: Int) {
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val entity = cachedPlayers[playerId]
            ?.takeIf { it.level() === level }
            ?: RemotePlayer(level, profile).also { cachedPlayers[playerId] = it }
        entity.tickCount = level.gameTime.toInt()
        entity.yBodyRot = PvpRoomPlayerModelSpec.BODY_YAW
        entity.yRot = PvpRoomPlayerModelSpec.BODY_YAW
        entity.yHeadRot = PvpRoomPlayerModelSpec.HEAD_YAW
        entity.xRot = PvpRoomPlayerModelSpec.PITCH
        InventoryScreen.renderEntityInInventory(
            graphics,
            x.toFloat(),
            centerY.toFloat(),
            scale.toFloat(),
            Vector3f(0f, entity.bbHeight / 2f, 0f),
            Quaternionf().rotateZ(Math.PI.toFloat()),
            Quaternionf(),
            entity,
        )
    }
}
