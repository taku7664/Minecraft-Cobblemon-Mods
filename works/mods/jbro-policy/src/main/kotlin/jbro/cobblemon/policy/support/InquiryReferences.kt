package jbro.cobblemon.policy.support

/** Bounded search of public server data; missing matches never establish absence. */
internal object InquiryReferences {
    data class Document(val source: String, val text: String)

    fun terms(question: String, translations: Map<String, String>): Set<String> {
        val compact = compact(question)
        val found = linkedSetOf<String>()
        for ((key, value) in translations) {
            val name = compact(value)
            if (name.length >= 2 && compact.contains(name)) {
                found += key.removeSuffix(".name").substringAfterLast('.').lowercase()
                found += name
            }
        }
        Regex("[a-zA-Z0-9_:]{3,}").findAll(question).forEach { found += it.value.lowercase() }
        return found.filter { it.length >= 2 }.take(24).toSet()
    }

    fun select(documents: List<Document>, terms: Set<String>, budget: Int = 12_000): String {
        if (terms.isEmpty()) return PichuRules.current().text("ai.referencesNoTerms")
        val selected = documents.filter { doc -> matches(doc.source + " " + doc.text, terms) }
        return buildString {
            append(PichuRules.current().text("ai.referencesHeader")).append('\n')
            for (document in selected) {
                val text = "\n[${document.source}]\n${document.text}\n"
                if (length + text.length > budget) continue
                append(text)
            }
            if (selected.isEmpty()) append(PichuRules.current().text("ai.referencesNone"))
        }
    }

    fun matches(text: String, terms: Set<String>): Boolean {
        val searchable = compact(text)
        return terms.any { searchable.contains(compact(it)) }
    }

    private val separators = Regex("[\\s:_\\-]")
    private fun compact(value: String) = value.lowercase().replace(separators, "")
}
