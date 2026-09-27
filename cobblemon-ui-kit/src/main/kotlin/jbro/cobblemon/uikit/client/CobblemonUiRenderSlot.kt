package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import com.mojang.authlib.GameProfile
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiModelPlacement
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.PlayerSkin as MinecraftPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import kotlin.math.min

sealed interface CobblemonUiRenderContent {
    data object Empty : CobblemonUiRenderContent
    data class Texture(val texture: UiIcon) : CobblemonUiRenderContent
    data class Item(val stack: ItemStack) : CobblemonUiRenderContent
    data class PlayerSkin(
        val texture: UiIcon,
        val slim: Boolean = false,
        val framing: UiModelFraming = UiModelFraming.PORTRAIT
    ) : CobblemonUiRenderContent

    /** A player's own skin, looked up every frame so a skin that finishes downloading later still appears. */
    data class PlayerProfile(
        val profile: GameProfile,
        val framing: UiModelFraming = UiModelFraming.PORTRAIT
    ) : CobblemonUiRenderContent
}

class CobblemonUiRenderSlot private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiRenderSlotSpec,
    val content: CobblemonUiRenderContent
) : AbstractWidget(x, y, width, height, spec.accessibleLabel) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        UiSurfaceRenderer.draw(graphics, x, y, width, height, theme.surfaces.panelAlt)
        val left = x + spec.padding
        val top = y + spec.padding
        val innerWidth = (width - spec.padding * 2).coerceAtLeast(1)
        val innerHeight = (height - spec.padding * 2).coerceAtLeast(1)
        graphics.enableScissor(left, top, left + innerWidth, top + innerHeight)
        try {
            when (content) {
                CobblemonUiRenderContent.Empty -> renderFallback(graphics, left, top, innerWidth, innerHeight)
                is CobblemonUiRenderContent.Texture -> renderTexture(graphics, content.texture, left, top, innerWidth, innerHeight)
                is CobblemonUiRenderContent.Item -> graphics.renderItem(content.stack, left + (innerWidth - 16) / 2, top + (innerHeight - 16) / 2)
                is CobblemonUiRenderContent.PlayerSkin -> renderPlayer(
                    graphics,
                    ResourceLocation.fromNamespaceAndPath(content.texture.namespace, content.texture.path),
                    content.slim,
                    UiModelPlacement.calculate(UiRect(left, top, innerWidth, innerHeight), content.framing)
                )
                is CobblemonUiRenderContent.PlayerProfile -> {
                    val skin = Minecraft.getInstance().skinManager.getInsecureSkin(content.profile)
                    renderPlayer(
                        graphics,
                        skin.texture(),
                        skin.model() == MinecraftPlayerSkin.Model.SLIM,
                        UiModelPlacement.calculate(UiRect(left, top, innerWidth, innerHeight), content.framing)
                    )
                }
            }
        } finally {
            graphics.disableScissor()
        }
    }

    private fun renderFallback(graphics: GuiGraphics, left: Int, top: Int, innerWidth: Int, innerHeight: Int) {
        spec.fallbackIcon?.let { renderTexture(graphics, it, left, top, innerWidth, innerHeight) } ?: run {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val marker = Component.literal("?")
            graphics.drawString(font, marker, left + (innerWidth - font.width(marker)) / 2, top + (innerHeight - font.lineHeight) / 2, theme.colors.textDim, false)
        }
    }

    private fun renderTexture(graphics: GuiGraphics, icon: UiIcon, left: Int, top: Int, innerWidth: Int, innerHeight: Int) {
        val size = min(8, min(innerWidth, innerHeight))
        graphics.blit(
            ResourceLocation.fromNamespaceAndPath(icon.namespace, icon.path),
            left + (innerWidth - size) / 2,
            top + (innerHeight - size) / 2,
            0f,
            0f,
            size,
            size,
            8,
            8
        )
    }

    private fun renderPlayer(graphics: GuiGraphics, texture: ResourceLocation, slim: Boolean, placement: UiModelPlacement) {
        val model = playerModel(slim)
        val scale = placement.scale.toFloat()
        val pose = graphics.pose()
        graphics.flush()
        pose.pushPose()
        try {
            pose.translate(placement.centerX.toDouble(), placement.originY.toDouble(), 80.0)
            pose.scale(scale, scale, -scale)
            val buffer = graphics.bufferSource().getBuffer(model.renderType(texture))
            model.renderToBuffer(pose, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
            graphics.flush()
        } finally {
            pose.popPose()
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, spec.accessibleLabel)
    }

    companion object {
        private var wideModel: PlayerModel<LivingEntity>? = null
        private var slimModel: PlayerModel<LivingEntity>? = null

        fun create(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            spec: UiRenderSlotSpec,
            content: CobblemonUiRenderContent = CobblemonUiRenderContent.Empty
        ): CobblemonUiRenderSlot {
            require(width > 0 && height > 0) { "Render slot size must be positive" }
            return CobblemonUiRenderSlot(x, y, width, height, spec, content)
        }

        private fun playerModel(slim: Boolean): PlayerModel<LivingEntity> {
            val existing = if (slim) slimModel else wideModel
            if (existing != null) return existing
            val layer = if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER
            return PlayerModel<LivingEntity>(Minecraft.getInstance().entityModels.bakeLayer(layer), slim).also { model ->
                model.setAllVisible(true)
                model.leftArm.zRot = -0.08f
                model.rightArm.zRot = 0.08f
                if (slim) slimModel = model else wideModel = model
            }
        }
    }
}
