package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Sample data only; geometry and rendering are shared with the live HUD. */
internal object BattleHudEdgeDraft {
    private const val ROW_STEP = 28

    fun render(context: DrawContext, screenWidth: Int, korean: Boolean) {
        val allies = listOf(
            card("pikachu", Gender.MALE, .72f, "86/120", .38f, "par"),
            card("bulbasaur", Gender.FEMALE, .91f, "91/100", .72f),
            card("eevee", Gender.FEMALE, .48f, "48/100", .54f)
        )
        val opponents = listOf(
            card("charizard", Gender.FEMALE, .36f, "36%", status = "brn"),
            card("venusaur", Gender.MALE, .54f, "54%", status = "par"),
            card("blastoise", Gender.MALE, .67f, "67%")
        )
        allies.forEachIndexed { index, pokemon ->
            BattleHudCardRenderer.draw(context, 0, 23 + index * ROW_STEP, true, pokemon)
        }
        opponents.forEachIndexed { index, pokemon ->
            BattleHudCardRenderer.draw(context, screenWidth - BattleHudCardRenderer.WIDTH,
                23 + index * ROW_STEP, false, pokemon)
        }
    }

    private fun card(species: String, gender: Gender, hp: Float, health: String,
                     experience: Float? = null, status: String? = null): BattleHudCardRenderer.Card =
        BattleHudCardRenderer.Card(
            uuid = UUID.nameUUIDFromBytes("hud-edge-$species".toByteArray()),
            species = Identifier.of("cobblemon", species),
            aspects = emptySet(),
            name = Text.translatable("cobblemon.species.$species.name").string,
            gender = gender,
            level = 50,
            hp = hp,
            health = health,
            experience = experience,
            status = status
        )
}
