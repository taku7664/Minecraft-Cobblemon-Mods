package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedNativeCorpusTest {
    private fun pair(a: List<String>, b: List<String>) = JsonObject().apply {
        for ((side, ids) in listOf("p1" to a, "p2" to b)) add(side, JsonObject().apply {
            add("setIds", JsonArray().apply { ids.forEach(::add) })
        })
    }

    @Test
    fun `membership ignores seats roster order seed and result but not set identity`() {
        val a = listOf("one", "two", "three")
        val b = listOf("four", "five", "six")
        val first = pair(a, b)
        val swapped = pair(b.reversed(), a.reversed()).apply { addProperty("battleSeed", 99); addProperty("winner", "p1") }
        assertEquals(EmbeddedNativeCorpus.key(first), EmbeddedNativeCorpus.key(swapped))
        assertEquals(EmbeddedNativeCorpus.split(first), EmbeddedNativeCorpus.split(swapped))
        assertNotEquals(EmbeddedNativeCorpus.key(first), EmbeddedNativeCorpus.key(pair(a, listOf("other", "five", "six"))))
        assertNotEquals(EmbeddedNativeCorpus.key(pair(listOf("a,b", "c"), b)),
            EmbeddedNativeCorpus.key(pair(listOf("a", "b,c"), b)))
    }

    @Test
    fun `previously observed native pair remains tuning in either seat`() {
        val first = pair(listOf("golem_preset_1", "decidueyehisui_preset_4", "delphox_preset_4"),
            listOf("lapras_preset_3", "mukalola_preset_1", "gliscor_preset_1"))
        assertEquals("d898e069f84224d6841fc3a7d01a54309dd0483bc17079177f49ca100396d835", EmbeddedNativeCorpus.key(first))
        assertEquals(EvaluationSplit.TUNING, EmbeddedNativeCorpus.split(first))
        assertEquals(EvaluationSplit.TUNING, EmbeddedNativeCorpus.split(EmbeddedNativePairs.orient(first, true)))
    }
}
