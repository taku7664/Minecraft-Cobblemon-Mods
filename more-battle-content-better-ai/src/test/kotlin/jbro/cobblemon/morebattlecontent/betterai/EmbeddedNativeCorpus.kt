package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject

/** Stable pair identity, not a species/set-disjoint split or a guarantee of unseen results. */
internal object EmbeddedNativeCorpus {
    // The three team pairs already used in native captures before this partition existed.
    val reservedTuningKeys = setOf(
        "d898e069f84224d6841fc3a7d01a54309dd0483bc17079177f49ca100396d835",
        "18cbcbe4790996134bf7fa107bd0e45daa130604f488570c7571e02145ce2dcb",
        "4572bc94f8880990521eca24389142bd88a5939ad82d2c77ff83f5cce6f55b41",
    )

    fun key(pair: JsonObject): String {
        fun encode(values: List<String>) = values.sorted().joinToString("") { "${it.length}:$it" }
        val teams = listOf("p1", "p2").map { side ->
            encode(pair.getAsJsonObject(side).getAsJsonArray("setIds").map { it.asString })
        }
        return LocalBaselineCapture.digest(("native-single-set-pair-v1|" + encode(teams)).toByteArray(Charsets.UTF_8))
    }

    fun split(pair: JsonObject): EvaluationSplit {
        val key = key(pair)
        return if (key !in reservedTuningKeys && key.take(8).toLong(16) % 5 == 0L) EvaluationSplit.HOLDOUT else EvaluationSplit.TUNING
    }
}
