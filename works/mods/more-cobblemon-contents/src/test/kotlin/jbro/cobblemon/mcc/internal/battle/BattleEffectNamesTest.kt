package jbro.cobblemon.mcc.internal.battle

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.internal.battle.BattleEffectNames.Kind
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleEffectNamesTest {
    /** Stands in for Cobblemon's registries, which unit tests do not load. */
    private val registered = mapOf(
        Kind.ABILITY to setOf("orichalcumpulse", "intimidate"),
        Kind.ITEM to setOf("leftovers", "blacksludge"),
        Kind.MOVE to setOf("taunt", "tackle"),
        Kind.TYPE to setOf("fire"),
    )

    private fun lookup(kind: Kind, id: String): Component? =
        if (id in registered.getValue(kind)) Component.translatable("${kind.name.lowercase()}.$id") else null

    private fun localized(key: String, vararg args: Any?): List<Any?> =
        arrayOf(*args).also { BattleEffectNames.localize(key, it, ::lookup) }.toList()

    private fun keyOf(value: Any?): String? = ((value as? Component)?.contents as? TranslatableContents)?.key

    @Test
    fun `an ability popup names the ability in the reader's language`() {
        val pokemon = Component.literal("Koraidon")
        val args = localized("ability.generic", pokemon, "Orichalcum Pulse")
        assertSame(pokemon, args[0])
        assertEquals("ability.orichalcumpulse", keyOf(args[1]))
    }

    @Test
    fun `item lines name the held item`() {
        assertEquals("item.leftovers", keyOf(localized("heal.leftovers", Component.literal("Snorlax"), "Leftovers")[1]))
        assertEquals("item.blacksludge", keyOf(localized("damage.item", Component.literal("Muk"), "Black Sludge")[1]))
    }

    @Test
    fun `effect lines look the name up as a move, then an ability, an item and a type`() {
        assertEquals("move.taunt", keyOf(localized("activate.forewarn", null, null, "Taunt")[2]))
        assertEquals("ability.intimidate", keyOf(localized("activate.skillswap", null, null, "Intimidate")[2]))
        assertEquals("type.fire", keyOf(localized("start.typechange", null, "Fire")[1]))
    }

    @Test
    fun `text that names nothing, and lines without names, stay as they were`() {
        // Spite's PP count and an unknown effect pass through.
        assertEquals(listOf(null, "move.tackle", "4"), localized("activate.spite", null, "Tackle", "4").map { keyOf(it) ?: it })
        assertEquals("Mystery Thing", localized("start.something", null, "Mystery Thing")[1])
        // An ability line never reads its text as an item, and other lines are left alone.
        assertEquals("Leftovers", localized("ability.generic", null, "Leftovers")[1])
        assertEquals("Taunt", localized("used_move", null, "Taunt")[1])
    }

    @Test
    fun `the lines it rewrites exist in Cobblemon with the name as a text argument`() {
        val english = javaClass.classLoader.getResources("assets/cobblemon/lang/en_us.json").toList()
            .filter { it.protocol == "jar" && !it.path.contains("more-cobblemon-contents") }
            .map { url -> url.openStream().reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject } }
            .firstOrNull { it.has("cobblemon.battle.ability.generic") }
        assertTrue(english != null, "Cobblemon's language file is not on the test classpath")
        mapOf(
            "ability.generic" to 2, "ability.trace" to 3, "ability.receiver" to 2, "ability.replace" to 2,
            "damage.item" to 2, "heal.leftovers" to 2, "heal.item" to 2,
        ).forEach { (key, argument) ->
            assertTrue(BattleEffectNames.kindsFor(key) != null, "$key is not translated")
            val line = english!!["cobblemon.battle.$key"]?.asString
            assertTrue(line != null && "%$argument\$s" in line, "Cobblemon's $key no longer names something at %$argument\$s: $line")
        }
    }
}
