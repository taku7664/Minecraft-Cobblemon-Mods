package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPartySlot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf

internal data class MbcPokemonPortraitIdentity(
    val stateKey: String,
    val speciesId: String,
    val formId: String?,
) {
    companion object {
        fun factory(setId: String, speciesId: String, formId: String?) = MbcPokemonPortraitIdentity(
            stateKey = "factory:$setId",
            speciesId = speciesId,
            formId = formId,
        )

        fun offer(token: String, speciesId: String, formId: String?) = MbcPokemonPortraitIdentity(
            stateKey = "factory-offer:$token",
            speciesId = speciesId,
            formId = formId,
        )
    }
}

internal class MbcPokemonPortraitRenderer {
    private val states = HashMap<String, FloatingState>()

    fun render(
        graphics: GuiGraphics,
        pokemon: TowerPlayPartySlot,
        bounds: TowerPlayRect,
        partialTick: Float,
        animate: Boolean,
    ) {
        val currentPartyPokemon = runCatching {
            CobblemonClient.storage.party.findByUUID(pokemon.pokemonId)
        }.getOrNull()
        val renderable = currentPartyPokemon?.asRenderablePokemon()
            ?: renderablePokemon(pokemon.speciesId, null)
            ?: return
        render(graphics, pokemon.pokemonId.toString(), renderable, bounds, partialTick, animate)
    }

    fun render(
        graphics: GuiGraphics,
        identity: MbcPokemonPortraitIdentity,
        bounds: TowerPlayRect,
        partialTick: Float,
        animate: Boolean,
    ) {
        val renderable = renderablePokemon(identity.speciesId, identity.formId) ?: return
        render(graphics, identity.stateKey, renderable, bounds, partialTick, animate)
    }

    private fun render(
        graphics: GuiGraphics,
        stateKey: String,
        renderable: RenderablePokemon,
        bounds: TowerPlayRect,
        partialTick: Float,
        animate: Boolean,
    ) {
        val state = states.getOrPut(stateKey, ::FloatingState)
        val pose = TowerPokemonPortraitRenderSpec.forBounds(bounds)
        graphics.enableScissor(bounds.left, bounds.top, bounds.right, bounds.bottom)
        graphics.pose().pushPose()
        try {
            graphics.pose().translate(pose.anchorX, pose.anchorY, pose.depth)
            drawProfilePokemon(
                renderablePokemon = renderable,
                matrixStack = graphics.pose(),
                rotation = Quaternionf().rotationXYZ(
                    Math.toRadians(13.0).toFloat(),
                    Math.toRadians(35.0).toFloat(),
                    0f,
                ),
                state = state,
                partialTicks = if (animate) partialTick else 0f,
                scale = pose.scale,
                applyProfileTransform = pose.applyProfileTransform,
            )
        } finally {
            graphics.pose().popPose()
            graphics.disableScissor()
        }
    }

    private fun renderablePokemon(speciesId: String, formId: String?): RenderablePokemon? {
        val species = runCatching {
            PokemonSpecies.getByIdentifier(ResourceLocation.parse(speciesId))
        }.getOrNull() ?: return null
        val aspects = if (formId == null || formId == "normal") {
            emptySet()
        } else {
            runCatching { species.getFormByName(formId).aspects.toSet() }.getOrNull().orEmpty()
        }
        return RenderablePokemon(species, aspects, ItemStack.EMPTY)
    }
}

internal data class TowerPokemonPortraitRenderPose(
    val anchorX: Double,
    val anchorY: Double,
    val depth: Double,
    val scale: Float,
    val applyProfileTransform: Boolean,
)

internal object TowerPokemonPortraitRenderSpec {
    fun forBounds(bounds: TowerPlayRect) = TowerPokemonPortraitRenderPose(
        anchorX = bounds.left + bounds.width / 2.0,
        anchorY = bounds.top - 2.0,
        depth = 0.0,
        scale = (bounds.height * PORTRAIT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE),
        applyProfileTransform = true,
    )

    private const val PORTRAIT_SCALE = 0.72f
    private const val MIN_SCALE = 12.0f
    private const val MAX_SCALE = 20.0f
}
