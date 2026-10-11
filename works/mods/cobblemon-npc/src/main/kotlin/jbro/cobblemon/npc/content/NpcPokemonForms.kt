package jbro.cobblemon.npc.content

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityDimensions

/** Cobblemon's species data for an NPC that stands as a Pokemon, read the same way on the server and the client. */
object NpcPokemonForms {
    /** Cobblemon's own fallback when a species gives no standing eye height, as a share of the hitbox height. */
    private const val EYE_HEIGHT = 0.85f

    fun id(ref: NpcSkinRef.Pokemon): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ref.namespace, ref.path)

    /** The form [ref]'s aspects pick, or null while species data is missing or still loading. */
    fun form(ref: NpcSkinRef.Pokemon): FormData? = try {
        PokemonSpecies.getByIdentifier(id(ref))?.getForm(ref.aspects)
    } catch (_: RuntimeException) {
        null
    }

    /** The hitbox a wild Pokemon of [ref]'s form has: its form hitbox at its base scale, as Cobblemon sizes one. */
    fun dimensions(ref: NpcSkinRef.Pokemon): EntityDimensions? {
        val form = form(ref) ?: return null
        val box = form.hitbox.scale(form.baseScale)
        return box.withEyeHeight((form.species.standingEyeHeight ?: EYE_HEIGHT) * box.height)
    }
}
