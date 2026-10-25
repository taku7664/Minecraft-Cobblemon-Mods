package jbro.cobblemon.policy.support

/** Only a supported, read-only answer may close an inquiry without an operator. */
internal object InquiryAutoResolve {
    // A rule quotation does not turn a reported failure into a usage question.
    private val failureReport = Regex("안\\s*(?:돼|되|됨|보|나[오와]|열|들어|먹|켜|꺼|뜨|떠)|못\\s*(?:해|하|함|받|쓰|써|들어)|실패|오류|버그|사라|분실|미지급", RegexOption.IGNORE_CASE)

    fun guard(reason: String, verdict: InquiryVerdict): InquiryVerdict {
        if (verdict.category != InquiryVerdict.Category.QUESTION || !failureReport.containsMatchIn(reason)) return verdict
        return verdict.copy(
            category = InquiryVerdict.Category.OTHER,
            verdict = InquiryVerdict.Verdict.UNKNOWN,
            resolution = InquiryVerdict.Resolution.OPERATOR,
            operatorDetail = "기능 실패 신고를 사용법 질문으로 자동 종결할 수 없어요. 아래 AI 분석은 미확인 주장이 포함될 수 있어 원래 증상과 대조해야 해요.\n\n" + verdict.operatorDetail,
            playerSummary = "신고하신 실패 상황은 앞선 성공 기록이나 일반 사용 규칙만으로 정상이라고 확인할 수 없어요.",
        )
    }

    sealed interface Plan {
        data object Explain : Plan
        data class HandOver(val why: String) : Plan
    }

    fun plan(verdict: InquiryVerdict, reference: String): Plan {
        if (verdict.autoCommands.isNotEmpty()) return Plan.HandOver("자동 명령은 실행하지 않아요. 원래 지급 건과 중복 여부를 운영자가 확인해야 해요")
        if (verdict.category != InquiryVerdict.Category.QUESTION) {
            return Plan.HandOver("복구·보상·오류·건의·신고는 조사 자료와 함께 운영자에게 넘겨요")
        }
        if (verdict.resolution != InquiryVerdict.Resolution.AUTO || verdict.verdict != InquiryVerdict.Verdict.MATCH) {
            return Plan.HandOver("답을 확정할 자료가 부족해요")
        }
        if (verdict.playerSummary.isBlank() || verdict.suggestedActions.isNotEmpty() || verdict.suggestedCommands.isNotEmpty()) {
            return Plan.HandOver("답변 외에 확인하거나 조치할 일이 남아 있어요")
        }
        if (verdict.evidence.isEmpty() || !verdict.evidence.all { evidence ->
                val quote = evidence.trim()
                quote.length >= 12 && reference.lineSequence().any { line ->
                    line.contains(quote) && !line.startsWith("위키 문서 목록:") &&
                        !line.startsWith("현재 서버 자료 중") && !line.contains("미조회") &&
                        !line.contains("미확인") && !line.contains("찾지 못했")
                }
            }) {
            return Plan.HandOver("답변의 근거를 제공한 서버 자료에서 확인하지 못했어요")
        }
        return Plan.Explain
    }
}
