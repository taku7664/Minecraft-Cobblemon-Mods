package jbro.cobblemon.uikit.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.ProfileTransformType
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf

/** Draws Cobblemon profile portraits for render slots; kept apart so only this file touches Cobblemon's client. */
internal object CobblemonUiPokemonRenderer {
    private const val MAX_STATES = 256
    private const val PORTRAIT_SCALE = 0.72f
    private const val MIN_SCALE = 12f
    private const val MAX_SCALE = 48f

    /** Idle animation per shown Pokemon, oldest dropped first so long sessions do not keep every portrait. */
    private val states = object : LinkedHashMap<String, FloatingState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatingState>): Boolean = size > MAX_STATES
    }

    fun draw(graphics: GuiGraphics, bounds: UiRect, content: CobblemonUiRenderContent.Pokemon, partialTick: Float) {
        val renderable = lookup { renderable(content) } ?: return
        draw(graphics, bounds, content.stateKey, renderable, if (content.animate) partialTick else 0f)
    }

    fun draw(graphics: GuiGraphics, bounds: UiRect, content: CobblemonUiRenderContent.PartyPokemon, partialTick: Float) {
        val live = lookup { CobblemonClient.storage.party.findByUUID(content.pokemonId)?.asRenderablePokemon() }
        val renderable = live ?: content.fallback?.let { fallback -> lookup { renderable(fallback) } } ?: return
        draw(graphics, bounds, content.pokemonId.toString(), renderable, if (content.animate) partialTick else 0f)
    }

    private fun draw(graphics: GuiGraphics, bounds: UiRect, stateKey: String, renderable: RenderablePokemon, partialTick: Float) {
        val state = states.getOrPut(stateKey, ::FloatingState)
        val pose = graphics.pose()
        pose.pushPose()
        try {
            // The profile transform stands the model on this anchor, just above the top edge, facing the viewer.
            pose.translate(bounds.x + bounds.width / 2.0, bounds.y - 2.0, 0.0)
            drawProfilePokemon(
                renderablePokemon = renderable,
                matrixStack = pose,
                rotation = Quaternionf().rotationXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(35.0).toFloat(), 0f),
                state = state,
                partialTicks = partialTick,
                scale = (minOf(bounds.width, bounds.height) * PORTRAIT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE),
                profileTransformType = ProfileTransformType.PROFILE,
            )
        } finally {
            pose.popPose()
        }
    }

    private fun renderable(content: CobblemonUiRenderContent.Pokemon): RenderablePokemon? {
        val species = PokemonSpecies.getByIdentifier(content.speciesId) ?: return null
        return RenderablePokemon(species, content.aspects, ItemStack.EMPTY)
    }

    /** Species data can be missing or mid-reload on the client; a portrait is then simply skipped. */
    private inline fun <T> lookup(action: () -> T): T? = try {
        action()
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
