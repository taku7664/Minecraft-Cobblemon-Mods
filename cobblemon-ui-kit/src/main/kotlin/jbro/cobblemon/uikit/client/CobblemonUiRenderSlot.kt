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
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.VillagerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.PlayerSkin as MinecraftPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import java.util.UUID
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

    /**
     * A villager as its base skin, a [type] biome overlay and a [profession] overlay, the way vanilla layers them:
     * `textures/entity/villager/type/<type>.png` and `textures/entity/villager/profession/<profession>.png` in each
     * id's namespace. Cobblemon's nurse is `cobblemon:nurse_joy`.
     */
    data class Villager(
        val profession: ResourceLocation,
        val type: ResourceLocation = ResourceLocation.withDefaultNamespace("plains"),
        val framing: UiModelFraming = UiModelFraming.PORTRAIT
    ) : CobblemonUiRenderContent

    /** A player's face with its hat layer, from their own skin, as a square centered in the slot. */
    data class PlayerFace(val profile: GameProfile) : CobblemonUiRenderContent

    /**
     * A Cobblemon Pokemon's profile portrait. [aspects] select its form; [stateKey] keeps its idle animation
     * running across widget rebuilds, so give each shown Pokemon a stable key. A still portrait holds its pose.
     */
    data class Pokemon(
        val speciesId: ResourceLocation,
        val aspects: Set<String> = emptySet(),
        val stateKey: String,
        val animate: Boolean = true
    ) : CobblemonUiRenderContent

    /**
     * One of the viewer's own party Pokemon. The live party entry is drawn so its real form, shininess and
     * cosmetics show; [fallback] is drawn once it has left the party.
     */
    data class PartyPokemon(
        val pokemonId: UUID,
        val fallback: Pokemon? = null,
        val animate: Boolean = true
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
        val inner = UiRect(
            x + spec.padding,
            y + spec.padding,
            (width - spec.padding * 2).coerceAtLeast(1),
            (height - spec.padding * 2).coerceAtLeast(1)
        )
        drawContent(graphics, inner, content, partialTick, spec.fallbackIcon)
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

        /**
         * Draws [content] clipped to [bounds] without the slot surface, for widgets such as cards and buttons
         * that frame a model themselves.
         */
        fun drawContent(
            graphics: GuiGraphics,
            bounds: UiRect,
            content: CobblemonUiRenderContent,
            partialTick: Float,
            fallbackIcon: UiIcon? = null
        ) {
            if (bounds.width <= 0 || bounds.height <= 0) return
            graphics.enableScissor(bounds.x, bounds.y, bounds.right, bounds.bottom)
            try {
                when (content) {
                    CobblemonUiRenderContent.Empty -> renderFallback(graphics, fallbackIcon, bounds)
                    is CobblemonUiRenderContent.Texture -> renderTexture(graphics, content.texture, bounds)
                    is CobblemonUiRenderContent.Item -> graphics.renderItem(
                        content.stack,
                        bounds.x + (bounds.width - 16) / 2,
                        bounds.y + (bounds.height - 16) / 2
                    )
                    is CobblemonUiRenderContent.PlayerSkin -> renderPlayer(
                        graphics,
                        ResourceLocation.fromNamespaceAndPath(content.texture.namespace, content.texture.path),
                        content.slim,
                        UiModelPlacement.calculate(bounds, content.framing)
                    )
                    is CobblemonUiRenderContent.PlayerProfile -> {
                        val skin = Minecraft.getInstance().skinManager.getInsecureSkin(content.profile)
                        renderPlayer(
                            graphics,
                            skin.texture(),
                            skin.model() == MinecraftPlayerSkin.Model.SLIM,
                            UiModelPlacement.calculate(bounds, content.framing)
                        )
                    }
                    is CobblemonUiRenderContent.Villager -> renderVillager(
                        graphics,
                        content,
                        UiModelPlacement.calculate(bounds, content.framing, UiModelPlacement.VILLAGER_HEAD)
                    )
                    is CobblemonUiRenderContent.PlayerFace -> {
                        val size = min(bounds.width, bounds.height)
                        PlayerFaceRenderer.draw(graphics, Minecraft.getInstance().skinManager.getInsecureSkin(content.profile),
                            bounds.x + (bounds.width - size) / 2, bounds.y + (bounds.height - size) / 2, size)
                    }
                    is CobblemonUiRenderContent.Pokemon -> CobblemonUiPokemonRenderer.draw(graphics, bounds, content, partialTick)
                    is CobblemonUiRenderContent.PartyPokemon -> CobblemonUiPokemonRenderer.draw(graphics, bounds, content, partialTick)
                }
            } finally {
                graphics.disableScissor()
            }
        }

        private fun renderFallback(graphics: GuiGraphics, fallbackIcon: UiIcon?, bounds: UiRect) {
            if (fallbackIcon != null) {
                renderTexture(graphics, fallbackIcon, bounds)
                return
            }
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val marker = Component.literal("?")
            graphics.drawString(
                font,
                marker,
                bounds.x + (bounds.width - font.width(marker)) / 2,
                bounds.y + (bounds.height - font.lineHeight) / 2,
                theme.colors.textDim,
                false
            )
        }

        private fun renderTexture(graphics: GuiGraphics, icon: UiIcon, bounds: UiRect) {
            val size = min(8, min(bounds.width, bounds.height))
            graphics.blit(
                ResourceLocation.fromNamespaceAndPath(icon.namespace, icon.path),
                bounds.x + (bounds.width - size) / 2,
                bounds.y + (bounds.height - size) / 2,
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

        private var villagerModel: VillagerModel<net.minecraft.world.entity.npc.Villager>? = null

        private fun renderVillager(graphics: GuiGraphics, content: CobblemonUiRenderContent.Villager, placement: UiModelPlacement) {
            val model = villagerModel ?: VillagerModel<net.minecraft.world.entity.npc.Villager>(
                Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.VILLAGER)
            ).also {
                it.young = false
                it.hatVisible(true)
                villagerModel = it
            }
            val layers = listOf(
                ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png"),
                ResourceLocation.fromNamespaceAndPath(content.type.namespace, "textures/entity/villager/type/${content.type.path}.png"),
                ResourceLocation.fromNamespaceAndPath(content.profession.namespace, "textures/entity/villager/profession/${content.profession.path}.png")
            )
            val scale = placement.scale.toFloat()
            val pose = graphics.pose()
            graphics.flush()
            pose.pushPose()
            try {
                pose.translate(placement.centerX.toDouble(), placement.originY.toDouble(), 80.0)
                pose.scale(scale, scale, -scale)
                layers.forEach { texture ->
                    model.renderToBuffer(pose, graphics.bufferSource().getBuffer(model.renderType(texture)),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
                    graphics.flush()
                }
            } finally {
                pose.popPose()
            }
        }

        private fun playerModel(slim: Boolean): PlayerModel<LivingEntity> {
            val existing = if (slim) slimModel else wideModel
            if (existing != null) return existing
            val layer = if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER
            return PlayerModel<LivingEntity>(Minecraft.getInstance().entityModels.bakeLayer(layer), slim).also { model ->
                model.setAllVisible(true)
                // EntityModel starts as young, which shrinks the body under a large head.
                model.young = false
                model.leftArm.zRot = -0.08f
                model.rightArm.zRot = 0.08f
                if (slim) slimModel = model else wideModel = model
            }
        }
    }
}
