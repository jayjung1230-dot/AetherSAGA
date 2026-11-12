package com.aether.reader.features.gemini

import com.aether.reader.data.L10n

data class ExpectationAnalysis(
    val summary: String,
    val searchKeyword: String,
    val discussionChips: List<String>,
    val fullDiscussionPrompt: String
)

object GeminiContextAnalyzer {

    fun analyze(
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        pageContext: String = ""
    ): ExpectationAnalysis {
        val q = quote.trim()
        val cleanKeyword = extractKeyword(q)
        val isShort = q.length <= 35
        val isLong = q.length > 120

        val summary = when {
            isShort -> {
                L10n.t(
                    "《$bookTitle》의 주요 핵심 개념입니다. 본문 문맥에서 중요한 의미와 역할을 지니며, 앞뒤 서술과 유기적으로 연결됩니다.",
                    "Key concept in 《$bookTitle》, playing a central role in connecting the surrounding narrative."
                )
            }
            isLong -> {
                L10n.t(
                    "문맥의 전후 인과관계와 중심 생각을 응축한 구절입니다. 전체적인 논점과 저자의 핵심 주장을 집약하고 있습니다.",
                    "A pivotal passage condensing the contextual cause-and-effect relationship and the author's core thesis."
                )
            }
            else -> {
                L10n.t(
                    "저자의 사유와 중심 메시지가 함축된 문장입니다. 행간에 담긴 은유와 본문 전개의 중요한 전환점을 시사합니다.",
                    "An insightful sentence embodying the author's message and marking a meaningful turning point in the text."
                )
            }
        }

        val chips = when {
            isShort -> listOf(
                L10n.t("💡 쉬운 일상 비유로 풀기", "💡 Explain with daily analogy"),
                L10n.t("🔗 이 챕터에서의 핵심 역할", "🔗 Core role in this chapter"),
                L10n.t("❓ 다른 관점과 비교하기", "❓ Compare with alternatives")
            )
            isLong -> listOf(
                L10n.t("📌 3줄 핵심 요약", "📌 3-line key summary"),
                L10n.t("⚖️ 원인과 파급 효과 토론", "⚖️ Discuss causes & impacts"),
                L10n.t("🚀 현대적 시사점과 교훈", "🚀 Modern lessons & insights")
            )
            else -> listOf(
                L10n.t("👍 저자의 핵심 의도 깊이 보기", "👍 In-depth author's intention"),
                L10n.t("⚖️ 반대 시각이나 비판적 질문", "⚖️ Critical counter-argument"),
                L10n.t("🌿 내 삶과 경험에 대입해보기", "🌿 Reflection on daily life")
            )
        }

        val fullPrompt = """
[도서 정보]
- 도서명: 《$bookTitle》
- 저자: $bookAuthor
- 챕터: $chapterTitle

[선택한 본문 문구]
"$q"

[토론 및 해설 요청]
위 문구의 맥락과 핵심 의미를 알기 쉽게 풀이해주고, 함께 깊이 사유해볼 수 있는 토론 질문과 통찰을 제공해주세요.
""".trimIndent()

        return ExpectationAnalysis(
            summary = summary,
            searchKeyword = cleanKeyword,
            discussionChips = chips,
            fullDiscussionPrompt = fullPrompt
        )
    }

    private fun extractKeyword(quote: String): String {
        // Strip punctuation and quotes
        val cleaned = quote.replace(Regex("[“\"'”‘’\\[\\](){},.:;?!]"), " ").trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return quote.take(20)

        // Prefer longer noun-like token or first 2 words
        val bestToken = tokens.maxByOrNull { it.length } ?: tokens[0]
        return if (bestToken.length in 2..15) {
            bestToken
        } else {
            tokens.take(2).joinToString(" ").take(20)
        }
    }
}
