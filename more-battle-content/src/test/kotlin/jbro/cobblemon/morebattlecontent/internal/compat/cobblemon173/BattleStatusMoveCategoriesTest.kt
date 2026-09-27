package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategories
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.DISRUPTION
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.FIELD
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.HAZARD
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.OTHER_STATUS
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.PROTECTION
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.RECOVERY
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory.STATUS_INFLICTION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BattleStatusMoveCategoriesTest {
    @Test
    fun `representative status moves fall into their categories from bundled Showdown facts`() {
        val expected = mapOf(
            "recover" to RECOVERY, "roost" to RECOVERY, "synthesis" to RECOVERY, "wish" to RECOVERY,
            "rest" to RECOVERY, "healbell" to RECOVERY,
            "protect" to PROTECTION, "detect" to PROTECTION, "kingsshield" to PROTECTION,
            "spore" to STATUS_INFLICTION, "willowisp" to STATUS_INFLICTION, "toxic" to STATUS_INFLICTION,
            "thunderwave" to STATUS_INFLICTION, "leechseed" to STATUS_INFLICTION, "yawn" to STATUS_INFLICTION,
            "taunt" to DISRUPTION, "encore" to DISRUPTION, "trick" to DISRUPTION, "roar" to DISRUPTION,
            "haze" to DISRUPTION, "charm" to DISRUPTION,
            "tailwind" to FIELD, "trickroom" to FIELD, "reflect" to FIELD, "raindance" to FIELD,
            "grassyterrain" to FIELD,
            "stealthrock" to HAZARD, "spikes" to HAZARD, "stickyweb" to HAZARD, "toxicspikes" to HAZARD,
            "substitute" to OTHER_STATUS, "destinybond" to OTHER_STATUS,
            // Callback-only effects that the declarative facts would misplace.
            "helpinghand" to OTHER_STATUS, "wideguard" to PROTECTION, "meanlook" to DISRUPTION,
            "psychoshift" to STATUS_INFLICTION, "ingrain" to RECOVERY,
        )

        val actual = expected.keys.associateWith { BattleStatusMoveCategories.classify(it, status(it)) }

        assertEquals(expected, actual)
    }

    @Test
    fun `damaging moves and pure self setup keep their own groups`() {
        assertNull(BattleStatusMoveCategories.classify("calmmind", status("calmmind")))
        assertNull(BattleStatusMoveCategories.classify("swordsdance", status("swordsdance")))
        assertNull(BattleStatusMoveCategories.classify(
            "earthquake",
            BattleMoveCandidateView("ground", BattleMoveDamageCategory.PHYSICAL, 100.0, 100.0, 0, 10,
                effects = Cobblemon173ShowdownMoveEffects.resolve("earthquake")),
        ))
    }

    /** Writes every standard status move's category for review; asserts only that each one resolves. */
    @Test
    fun `every standard status move receives a category or the pure setup group`() {
        val entries = statusMoveEntries()
        val rows = entries.map { (id, nonstandard) ->
            val details = status(id)
            val category = BattleStatusMoveCategories.classify(id, details)
            Triple(id, nonstandard, category?.name ?: "PURE_SETUP")
        }
        val report = buildString {
            rows.groupBy { it.third }.toSortedMap().forEach { (category, members) ->
                appendLine("$category (${members.size})")
                appendLine("  " + members.joinToString(", ") { if (it.second) "${it.first}*" else it.first })
            }
            appendLine("* = isNonstandard (Past/Future/Unobtainable)")
        }
        val out = Path.of("build", "reports", "status-move-categories.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)

        assert(entries.size > 200) { "Bundled Showdown must contain the full status move list, found ${entries.size}" }
    }

    private fun status(id: String) = BattleMoveCandidateView(
        "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
        effects = Cobblemon173ShowdownMoveEffects.resolve(id),
    )

    /** Top-level entries of the bundled moves.js whose category is Status. */
    private fun statusMoveEntries(): List<Pair<String, Boolean>> {
        val source = javaClass.classLoader.getResourceAsStream("data/cobblemon/showdown.zip").use { input ->
            ZipInputStream(requireNotNull(input)).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                zip.readBytes().toString(StandardCharsets.UTF_8)
            }
        }
        val starts = ENTRY.findAll(source).toList()
        return starts.mapIndexedNotNull { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: source.length
            val body = source.substring(match.range.first, end)
            if (!body.contains("category: \"Status\"")) return@mapIndexedNotNull null
            match.groupValues[1] to body.contains("isNonstandard:")
        }
    }

    private companion object {
        val ENTRY = Regex("""(?m)^  ([a-z0-9]+): \{""")
    }
}
