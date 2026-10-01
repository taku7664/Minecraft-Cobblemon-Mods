package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.status.PersistentStatus
import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.UUID
import kotlin.math.ceil

/** Adapts Cobblemon's animated battle tile data to the reviewed edge HUD card. */
object BattleHudRenderer {
    @JvmStatic
    fun draw(context: DrawContext, nativeX: Float, nativeY: Float, reversed: Boolean, species: Species,
             level: Int, displayName: Text, gender: Gender, status: PersistentStatus?,
             state: PosableState, opacity: Float, maxHealth: Int, health: Float,
             selected: Boolean, hovered: Boolean, compact: Boolean,
             actorName: Text?, flatHealth: Boolean, dexState: PokedexEntryProgress) {
        val nativeWidth = if (compact) BattleOverlay.COMPACT_TILE_WIDTH else BattleOverlay.TILE_WIDTH
        val battleType = CobblemonClient.battle?.battleFormat?.battleType
        val indent = if (compact && battleType != null) BattleScreenGeometry.compactHudSlotIndent(
            nativeY, battleType.slotsPerActor, battleType.actorsPerSide) else 0
        val compression = if (compact && battleType != null) BattleScreenGeometry.compactHudRowCompression(
            nativeY, battleType.slotsPerActor, battleType.actorsPerSide) else 0
        // Keep native slide animation, but settle every row flush against the screen edge.
        val x = (if (reversed) nativeX + nativeWidth + BattleOverlay.HORIZONTAL_INSET -
            BattleHudCardRenderer.WIDTH + indent else nativeX - BattleOverlay.HORIZONTAL_INSET - indent).toInt()
        val y = nativeY.toInt() + 13 - compression
        val ratio = (if (flatHealth) health / maxHealth.coerceAtLeast(1) else health).coerceIn(0f, 1f)
        val pokemonId = BattleHudContext.pokemonId
        val card = BattleHudCardRenderer.Card(
            uuid = pokemonId ?: UUID.nameUUIDFromBytes(
                "${species.resourceIdentifier}-$reversed-${displayName.string}".toByteArray()),
            species = species.resourceIdentifier,
            aspects = state.currentAspects.toSet(),
            name = displayName.string,
            gender = gender,
            level = level,
            hp = ratio,
            health = if (flatHealth) "${health.toInt()}/$maxHealth" else "${ceil(ratio * 100).toInt()}%",
            experience = if (!reversed && pokemonId != null) experienceProgress(pokemonId, level) else null,
            status = status?.showdownName,
            owned = dexState == PokedexEntryProgress.OWNED,
            actorName = actorName,
            selected = selected,
            hovered = hovered,
            opacity = opacity
        )
        BattleHudCardRenderer.draw(context, x, y, !reversed, card)
    }

    private fun experienceProgress(uuid: UUID, battleLevel: Int): Float? {
        val pokemon = CobblemonClient.storage.party.findByUUID(uuid) ?: return null
        if (pokemon.level != battleLevel || battleLevel >= Cobblemon.config.maxPokemonLevel) return null
        val group = pokemon.experienceGroup
        val start = group.getExperience(battleLevel)
        val end = group.getExperience(battleLevel + 1)
        if (end <= start) return null
        return ((pokemon.experience - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}
