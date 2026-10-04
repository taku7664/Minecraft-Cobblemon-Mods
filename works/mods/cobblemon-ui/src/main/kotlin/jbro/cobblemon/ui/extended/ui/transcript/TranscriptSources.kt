package jbro.cobblemon.ui.extended.ui.transcript

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.ui.extended.BattleLog
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Public, client-visible Pokémon identity captured when a message arrives, never at render time. */
data class TranscriptSpeaker(
    val uuid: UUID,
    val left: Boolean,
    val ownerName: String,
    val pokemonName: Component,
    val species: ResourceLocation,
    val aspects: Set<String>,
    val wild: Boolean = false
)

object TranscriptSources {
    private var battleId: UUID? = null
    private val known = linkedMapOf<UUID, TranscriptSpeaker>()

    fun clear() { known.clear(); battleId = null }

    fun capture(messages: List<Component>) {
        val battle = CobblemonClient.battle ?: return
        if (battleId != battle.battleId) {
            // Initialization already cleared history and initialized HP tracking.
            // Do not erase that HP baseline again on the first message packet.
            if (battleId != null) BattleLog.clear()
            battleId = battle.battleId
        }
        val playerId = Minecraft.getInstance().player?.uuid
        // Matches Cobblemon's own overlay, including side2 on the left when spectating.
        val left = if (battle.side1.actors.any { it.uuid == playerId }) battle.side1 else battle.side2
        battle.sides.forEach { side ->
            side.actors.forEach { actor ->
                actor.activePokemon.forEach activeLoop@ { active ->
                    val pokemon = active.battlePokemon ?: return@activeLoop
                    known[pokemon.uuid] = TranscriptSpeaker(pokemon.uuid, side === left,
                        actor.displayName.string, pokemon.displayName.copy(), pokemon.species.resourceIdentifier,
                        pokemon.state.currentAspects.toSet(), actor.type == ActorType.WILD)
                }
            }
        }
        BattleLog.processMessages(messages) { message -> resolve(message, known.values.toList()) }
    }

    /** Never infer ownership from translated possessive grammar or "the opposing" text. */
    fun resolve(message: Component, candidates: List<TranscriptSpeaker>): TranscriptSpeaker? {
        val content = battleContent(message) ?: return null
        val first = content.args.firstOrNull() ?: return null
        val nested = when (first) {
            is Component -> first.contents as? TranslatableContents
            is TranslatableContents -> first
            else -> null
        }
        if (nested?.key == "cobblemon.battle.owned_pokemon" && nested.args.size >= 2) {
            val owner = value(nested.args[0])
            val name = value(nested.args[1])
            return candidates.filter { it.ownerName == owner && it.pokemonName.string == name }.singleOrNull()
        }
        // Wild names have no owner wrapper. Only a unique, known wild identity may match.
        val name = value(first)
        return candidates.filter { it.wild && it.pokemonName.string == name }.singleOrNull()
    }

    private fun value(arg: Any): String = when (arg) {
        is Component -> arg.string
        is TranslatableContents -> Component.translatable(arg.key, *arg.args).string
        else -> arg.toString()
    }

    fun battleContent(text: Component): TranslatableContents? {
        val content = text.contents as? TranslatableContents
        if (content?.key?.startsWith("cobblemon.battle.") == true) return content
        return text.siblings.firstNotNullOfOrNull(::battleContent)
    }
}
