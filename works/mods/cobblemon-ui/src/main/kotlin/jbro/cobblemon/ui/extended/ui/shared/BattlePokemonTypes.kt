package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.pokemon.Species
import jbro.cobblemon.ui.extended.BattleStateTracker
import net.minecraft.client.gui.GuiGraphics
import java.util.UUID

/** A battler's types as the battle currently has them, and the small icons the HUD and information window draw. */
object BattlePokemonTypes {
    const val ICON_SIZE = 9
    const val ICON_STEP = ICON_SIZE + 1

    /** Terastallized type first, then tracked changes (Soak, Burn Up, form changes), then the form's own types. */
    fun current(uuid: UUID?, species: Species?, aspects: Set<String>): List<ElementalType> {
        if (uuid != null) {
            BattleStateTracker.getTeraType(uuid)?.let { tera ->
                ElementalTypes.get(tera.lowercase())?.let { return listOf(it) }
            }
            BattleStateTracker.getDynamicTypes(uuid)?.let { state ->
                return (listOfNotNull(state.primaryType, state.secondaryType) + state.addedTypes)
                    .mapNotNull { ElementalTypes.get(it.lowercase()) }
                    .distinct()
            }
        }
        val form = species?.getForm(aspects) ?: return emptyList()
        return listOfNotNull(form.primaryType, form.secondaryType)
    }

    fun width(types: List<ElementalType>): Int = if (types.isEmpty()) 0 else types.size * ICON_STEP - 1

    /** Icons top to bottom from [y], one column at [x]; returns the bottom edge of the last one. */
    fun drawIconsDown(context: GuiGraphics, types: List<ElementalType>, x: Int, y: Int, opacity: Float = 1f): Int {
        types.forEachIndexed { index, type ->
            TypeIcon(x = x, y = y + index * ICON_STEP, type = type, small = true, opacity = opacity).render(context)
        }
        return y + width(types)
    }

    /** Icons left to right from [x]; returns the right edge of the last one. */
    fun drawIcons(context: GuiGraphics, types: List<ElementalType>, x: Int, y: Int, opacity: Float = 1f): Int {
        types.forEachIndexed { index, type ->
            TypeIcon(x = x + index * ICON_STEP, y = y, type = type, small = true, opacity = opacity).render(context)
        }
        return x + width(types)
    }
}
