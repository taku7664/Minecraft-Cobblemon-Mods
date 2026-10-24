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
        if (terms.isEmpty()) return "조회할 아이템·기술·포켓몬 식별자를 찾지 못했어요. 자료 미조회이며 존재하지 않는다는 뜻이 아니에요."
        val selected = documents.filter { doc -> matches(doc.source + " " + doc.text, terms) }
        return buildString {
            append("현재 서버 자료 중 검색어와 일치한 항목만 발췌했어요. 누락·미조회 자료가 있어 검색 실패는 부재의 증거가 아니에요.\n")
            for (document in selected) {
                val text = "\n[${document.source}]\n${document.text}\n"
                if (length + text.length > budget) continue
                append(text)
            }
            if (selected.isEmpty()) append("일치하는 자료를 찾지 못했어요. 존재 여부는 미확인이에요.")
        }
    }

    fun matches(text: String, terms: Set<String>): Boolean {
        val searchable = compact(text)
        return terms.any { searchable.contains(compact(it)) }
    }

    private val separators = Regex("[\\s:_\\-]")
    private fun compact(value: String) = value.lowercase().replace(separators, "")
}
