package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aether.reader.App
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Intelligent in-app Gemini AI Reading Assistant & Discussion Dialog.
 *
 * Automatically analyzes selected text/highlights in context of the book's title,
 * author, and chapter. Dynamically adapts between:
 *  - Literary & Humanities Mode (문학·사유)
 *  - Textbook & Tech Tutor Mode (교과서·기술 튜터)
 *  - Historical Context Mode (역사·인과관계)
 *
 * Supports:
 *  - 100% In-App 1:1 live conversational chat via Google Gemini REST API (gemini-1.5-flash)
 *  - Contextual quick-reply suggestion chips (ELI5, 3-step mechanism, critical questions)
 *  - Saving complete chat dialogue into Room DB reading notes
 *  - Seamless fallback to official external Gemini app / web
 */
object GeminiDialogHelper {

    enum class GenreMode {
        LITERATURE,
        TECH,
        HISTORY
    }

    data class ModeInsight(
        val badge1: String,
        val badge2: String,
        val section1Title: String,
        val section1Body: String,
        val section2Title: String,
        val section2Body: String,
        val promptQuestion: String,
        val discussionPrompt: String
    )

    data class ChatMessage(
        val role: String,
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun detectGenre(title: String, author: String, chapter: String, quote: String): GenreMode {
        val q = quote.lowercase()
        val t = "$title $chapter $author".lowercase()

        var techScore = 0
        var histScore = 0
        var litScore = 0

        // Title / Chapter explicit indicators
        val techDocKeywords = listOf(
            "설명서", "사용법", "가이드", "매뉴얼", "튜토리얼", "guide", "manual", "tutorial",
            "doc", "spec", "사양서", "교과서", "기술", "공학", "아키텍처", "개발팀", "개발자", "입문"
        )
        if (techDocKeywords.any { t.contains(it) }) techScore += 8

        val histDocKeywords = listOf(
            "역사", "실록", "연대기", "사기", "전쟁사", "한국사", "세계사", "history", "chronicle", "dynasty", "사료", "평전"
        )
        if (histDocKeywords.any { t.contains(it) }) histScore += 8

        val litDocKeywords = listOf(
            "소설", "시집", "시선집", "문학", "에세이", "수필", "희곡", "동화", "novel", "poetry", "poem", "fiction", "이야기"
        )
        if (litDocKeywords.any { t.contains(it) }) litScore += 8

        // Quote content indicators
        val techWords = listOf(
            "전자종이", "e-ink", "eink", "단말기", "태블릿", "lcd", "oled", "디스플레이", "화면", "해상도", "dpi", "ppi",
            "반응성", "주사율", "리프레시", "잔상", "백라이트", "색온도", "블루라이트", "눈 편한", "가독성", "배터리",
            "시스템", "소프트웨어", "하드웨어", "엔진", "아키텍처", "프로그래밍", "코드", "알고리즘", "네트워크",
            "운영체제", "os", "linux", "kernel", "안드로이드", "android", "sdk", "api", "라이브러리", "최적화",
            "성능", "메모리", "캐시", "cpu", "gpu", "스토리지", "데이터", "데이터베이스", "db", "room", "sqlite",
            "webdav", "동기화", "클라우드", "백업", "복원", "조판", "타이포그래피", "폰트", "글꼴", "여백", "행간",
            "스트로크", "뷰어", "렌더링", "캔버스", "비트맵", "epub", "pdf", "터치", "제스처", "ui", "ux", "극대화",
            "cache", "memory", "latency", "architecture", "computer", "system", "hardware", "software",
            "algorithm", "network", "server", "client", "database", "engineering", "optimization", "render"
        )
        for (k in techWords) {
            if (q.contains(k)) techScore += 3
            else if (t.contains(k)) techScore += 1
        }

        val histWords = listOf(
            "역사", "사건", "전쟁", "혁명", "독립", "식민", "조약", "협정", "왕조", "황제", "국왕", "대통령", "정부",
            "정치", "제국", "문명", "세기", "연대", "실록", "고대", "중세", "근대", "현대", "조선", "고려", "삼국",
            "신라", "백제", "고구려", "로마", "세계대전", "냉전", "민주주의", "자본주의", "사회주의", "봉건", "계급",
            "시민", "영토", "항쟁", "봉기", "bc", "ad", "century", "dynasty", "war", "empire", "revolution"
        )
        for (k in histWords) {
            if (q.contains(k)) histScore += 3
            else if (t.contains(k)) histScore += 1
        }

        val litWords = listOf(
            "인간", "사랑", "이별", "삶", "인생", "죽음", "고독", "외로움", "슬픔", "기쁨", "행복", "눈물", "운명",
            "자아", "내면", "영혼", "마음", "심장", "기억", "추억", "시간", "세월", "꿈", "희망", "절망", "별",
            "바람", "하늘", "바다", "달", "햇살", "그림자", "어둠", "빛", "길", "여정", "침묵", "소리", "고백", "방황"
        )
        for (k in litWords) {
            if (q.contains(k)) litScore += 2
            else if (t.contains(k)) litScore += 1
        }

        return when {
            techScore >= histScore && techScore >= litScore && techScore > 0 -> GenreMode.TECH
            histScore >= techScore && histScore >= litScore && histScore > 0 -> GenreMode.HISTORY
            else -> GenreMode.LITERATURE
        }
    }

    private fun generateInsight(mode: GenreMode, title: String, author: String, chapter: String, quote: String): ModeInsight {
        val q = quote.lowercase()
        val t = "$title $chapter".lowercase()

        val isDisplayHardware = listOf("전자종이", "e-ink", "eink", "단말기", "태블릿", "lcd", "oled", "디스플레이", "눈 편한", "반응성", "주사율", "리프레시", "잔상", "배터리").any { q.contains(it) || t.contains(it) }
        val isSoftwareArchitecture = listOf("엔진", "렌더링", "캔버스", "캐시", "메모리", "webdav", "동기화", "클라우드", "백업", "아키텍처", "성능", "경량화", "최적화", "알고리즘", "데이터", "네트워크").any { q.contains(it) }
        val isTypography = listOf("조판", "타이포그래피", "폰트", "글꼴", "여백", "행간", "자간", "스트로크", "가독성", "뷰어", "북마크", "목차").any { q.contains(it) }
        val isTimeLife = listOf("시간", "세월", "운명", "삶", "인생", "죽음", "자아", "존재", "영혼", "고독", "별", "순간", "영원").any { q.contains(it) }
        val isEmotionLove = listOf("사랑", "마음", "가슴", "슬픔", "눈물", "기쁨", "행복", "이별", "사람", "친구", "따뜻").any { q.contains(it) }
        val isHistoryWar = listOf("역사", "전쟁", "혁명", "왕조", "제국", "독립", "식민", "문명", "사건", "조약", "시대", "정치", "국가", "권력").any { q.contains(it) }

        return when (mode) {
            GenreMode.TECH -> {
                when {
                    isDisplayHardware -> ModeInsight(
                        badge1 = L10n.t("💡 3초 직관 이해 (ELI5)", "💡 3-Second ELI5"),
                        badge2 = L10n.t("📱 디스플레이 공학 & 가독성", "📱 Display Tech & Legibility"),
                        section1Title = L10n.t("💡 핵심 개념 & 일상 비유", "💡 Core Concept & Everyday Analogy"),
                        section1Body = L10n.t(
                            "반사형 디스플레이(E-Ink 마이크로캡슐 전기영동)와 발광형 디스플레이(LCD/OLED 백라이트·픽셀)의 물리적 특성을 사용 목적에 맞게 결합한 원리입니다.\n\n• 직관 비유: 직사광선 아래에서도 눈부심 없이 편안한 '종이책의 차분함'과, 손끝 터치에 즉각 응답하는 '스마트폰의 민첩함'을 하나의 뷰어 소프트웨어에서 최적화한 하이브리드 설계입니다.",
                            "Combines the reflective physics of E-Ink electrophoretic microcapsules with the high refresh responsiveness of emissive LCD/OLED screens.\n\n• Analogy: Harmonizes the glare-free calmness of real paper under bright sunlight with the lightning-quick touch responsiveness of modern tablets."
                        ),
                        section2Title = L10n.t("⚙️ 동작 메커니즘 3단계 분해", "⚙️ 3-Step Execution Logic"),
                        section2Body = L10n.t(
                            "1단계. 정독 상태: 흑백 대전 입자를 정지시켜 눈의 피로와 정지 화면 배터리 소모를 0W로 억제\n2단계. 페이지 전환: 고속 부분 갱신(Fast A2/Regal) 알고리즘으로 잔상을 지우며 반응 지연 최소화\n3단계. 환경 적응: 주변 조도와 기기 패널 특성에 맞춰 고대비 폰트 스트로크 및 색온도를 지능적으로 보정",
                            "Step 1 (Reading): Static particles freeze to eliminate flicker, eye strain, and idle battery drain.\nStep 2 (Turning): Fast partial refresh (A2/Regal) minimizes input latency while clearing ghosting.\nStep 3 (Adaptation): High-contrast edge stroke booster and color temperature adapt dynamically to ambient light."
                        ),
                        promptQuestion = L10n.t(
                            "📝 개념 점검 퀴즈: 야외 직사광선 아래에서는 왜 LCD/OLED보다 E-Ink가 훨씬 더 선명하게 보일까요? (외부 자연광 반사 vs 백라이트 투과 원리)",
                            "📝 Quick Check: Why is E-Ink clearer than LCD/OLED in direct sunlight? (Ambient reflection vs backlight transmission)"
                        ),
                        discussionPrompt = "이 문장은 전자종이(E-Ink)와 태블릿(LCD/OLED)의 디스플레이 특성 및 반응성에 대해 다루고 있어. 전자잉크의 미세캡슐 전기영동 원리와 LCD/OLED의 주사율 및 눈 피로도 차이를 기술적으로 비교해주고, 각각의 기기 환경에서 최적의 독서 경험을 설계하려면 어떤 소프트웨어 렌더링 최적화 기법이 필요한지 자세히 알려줘."
                    )
                    isSoftwareArchitecture -> ModeInsight(
                        badge1 = L10n.t("💡 아키텍처 원리", "💡 Architecture Logic"),
                        badge2 = L10n.t("⚡ 성능 최적화", "⚡ Performance Tuning"),
                        section1Title = L10n.t("💡 시스템 구조 & 직관 비유", "💡 System Architecture & Analogy"),
                        section1Body = L10n.t(
                            "무거운 외부 프레임워크에 의존하지 않고, 필요한 데이터와 렌더링 파이프라인을 가장 짧은 경로로 직접 처리하는 경량 아키텍처의 원리입니다.\n\n• 직관 비유: 매번 먼 중앙 창고에 다녀오는 대신 자주 쓰는 도구를 작업대 손 닿는 곳에 정돈해 두어 작업 지연을 없애는 것과 같습니다.",
                            "A lightweight native architecture that eliminates redundant layers, processing data and layout rendering via the shortest direct path.\n\n• Analogy: Like keeping essential tools directly on your workbench rather than traveling to a distant warehouse each time."
                        ),
                        section2Title = L10n.t("⚙️ 3단계 처리 파이프라인", "⚙️ 3-Step Processing Pipeline"),
                        section2Body = L10n.t(
                            "1단계. 요청 감지: 사용자 입력 발생 시 불필요한 GC(가비지 컬렉션) 없이 메모리 풀 재활용\n2단계. 네이티브 렌더링: 안드로이드 순수 캔버스(Canvas) 기반 직접 드로잉으로 지연 시간 단축\n3단계. 로컬 영속화: 변경 사항을 Room DB와 WebDAV 동기화 큐에 비동기로 안전하게 반영",
                            "Step 1: Reuse preallocated memory pool without triggering garbage collection spikes.\nStep 2: Direct Canvas rendering minimizes latency and frame drops.\nStep 3: Asynchronously persist mutations to local Room DB and sync queues."
                        ),
                        promptQuestion = L10n.t(
                            "📝 개념 점검 퀴즈: 모바일 기기에서 가비지 컬렉터(GC) 빈도를 줄이는 것이 스크롤 부드러움에 왜 결정적일까요? (메모리 재할당과 프레임 드랍 방지)",
                            "📝 Quick Check: Why does reducing GC pause frequency directly ensure smooth 60fps scrolling?"
                        ),
                        discussionPrompt = "이 문장은 소프트웨어 엔진 구조와 성능 최적화에 대해 다루고 있어. 모바일/임베디드 환경에서 메모리 풀링, 네이티브 렌더링, 비동기 데이터 동기화 파이프라인을 어떻게 설계해야 최고의 반응성과 안정성을 얻을 수 있는지 공학적 관점에서 설명해줘."
                    )
                    isTypography -> ModeInsight(
                        badge1 = L10n.t("📐 조판 공학", "📐 Typography Engineering"),
                        badge2 = L10n.t("👁️ 시각 인지 과학", "👁️ Cognitive Vision"),
                        section1Title = L10n.t("💡 가독성 설계 원리", "💡 Readability Design Principles"),
                        section1Body = L10n.t(
                            "글자의 크기뿐만 아니라 행간(Line-height), 자간(Kerning), 화면 테두리 여백의 황금비율을 조율하여 인간의 시각 인지 피로를 최소화하는 조판 기술입니다.\n\n• 직관 비유: 빼곡한 주차장보다 적절한 간격이 있는 도로에서 차가 막힘없이 달리듯, 활자 사이의 여백이 시선의 흐름을 부드럽게 이끕니다.",
                            "Optimizes line-height, kerning, and screen margins based on cognitive vision science to reduce eye strain during prolonged reading.\n\n• Analogy: Just as well-spaced lanes allow smooth traffic flow, balanced typographic whitespace guides eye saccades effortlessly."
                        ),
                        section2Title = L10n.t("⚙️ 3단계 시선 유도 메커니즘", "⚙️ 3-Step Visual Guidance"),
                        section2Body = L10n.t(
                            "1단계. 초점 형성: 고대비 폰트 외곽선(Stroke)으로 활자의 시각적 경계 명확화\n2단계. 도약 운동(Saccade): 적정 행간을 유지하여 다음 줄로 넘어갈 때 줄 바꿈 착오 방지\n3단계. 인지 지속: 규칙적인 단락 여백으로 장시간 독서 시 뇌의 피로 누적 완화",
                            "Step 1: Sharp outline strokes reinforce glyph boundaries on lower-contrast screens.\nStep 2: Optimal line height prevents line-skipping errors during eye saccades.\nStep 3: Consistent rhythm reduces cognitive fatigue across multi-hour reading sessions."
                        ),
                        promptQuestion = L10n.t(
                            "📝 개념 점검 퀴즈: E-Ink 패널에서 왜 폰트의 외곽선 두께(Edge Stroke)를 일반 LCD보다 더 굵게 보정해야 할까요? (반사광 확산과 입자 경계 보정)",
                            "📝 Quick Check: Why must font edge stroke be slightly thickened on E-Ink compared to OLED?"
                        ),
                        discussionPrompt = "이 문장은 디지털 독서에서의 조판과 가독성 설계에 대해 다루고 있어. 시각 인지 심리학과 타이포그래피 공학 관점에서 장시간 독서 시 피로를 줄이기 위한 최적의 여백, 행간, 자간 비율과 폰트 렌더링 기술에 대해 심층적으로 설명해줘."
                    )
                    else -> ModeInsight(
                        badge1 = L10n.t("💡 개념 분해 (ELI5)", "💡 Concept Breakdown (ELI5)"),
                        badge2 = L10n.t("⚙️ 원리 분석", "⚙️ Mechanism Analysis"),
                        section1Title = L10n.t("💡 핵심 개념의 쉬운 이해", "💡 Plain-Language Interpretation"),
                        section1Body = L10n.t(
                            "이 문장은 본문에서 다루는 핵심 주제와 기능적 원리를 압축하여 설명하고 있습니다. 복잡한 세부 사항에 얽매이지 않고 전체적인 목적과 효용을 직관적으로 이해할 수 있는 출발점입니다.",
                            "This passage encapsulates the core mechanism and functional intent described in the chapter, providing an intuitive mental model before diving into technical details."
                        ),
                        section2Title = L10n.t("⚙️ 동작 메커니즘 3단계", "⚙️ 3-Step Functional Logic"),
                        section2Body = L10n.t(
                            "1단계. 목적 정의: 해결하고자 하는 핵심 요구사항과 목표 환경 파악\n2단계. 상호작용: 사용자 조작과 시스템 내부 로직 간의 유기적 데이터 흐름\n3단계. 결과 산출: 안정적이고 일관된 상태 유지 및 즉각적인 피드백 제공",
                            "Step 1: Clear identification of the functional requirement and target context.\nStep 2: Streamlined interaction between user input and internal system logic.\nStep 3: Reliable execution ensuring predictable outcomes and immediate feedback."
                        ),
                        promptQuestion = L10n.t(
                            "📝 개념 점검: 이 문장에서 강조하는 핵심 기능이 사용자에게 실제로 주는 가장 직접적인 가치는 무엇일까요?",
                            "📝 Concept Check: What is the primary operational value delivered by the core mechanism described in this sentence?"
                        ),
                        discussionPrompt = "이 문장의 핵심 기술적/학술적 개념을 비전공자도 쉽게 이해할 수 있는 일상 비유와 3단계 원리로 분해해서 설명해주고, 실제 응용 사례와 점검 퀴즈를 알려줘."
                    )
                }
            }
            GenreMode.LITERATURE -> {
                when {
                    isDisplayHardware || isSoftwareArchitecture || isTypography -> ModeInsight(
                        badge1 = L10n.t("🖋️ 매체 미학", "🖋️ Media Aesthetics"),
                        badge2 = L10n.t("📖 몰입의 기술", "📖 The Art of Immersion"),
                        section1Title = L10n.t("✨ 활자와 매체, 그리고 읽는 행위의 본질", "✨ Text, Medium & The Essence of Reading"),
                        section1Body = L10n.t(
                            "이 문장은 기술적 사양을 넘어 '인간이 텍스트에 깊이 몰입하는 최적의 환경'에 대한 사유를 담고 있습니다. 종이책의 아날로그적 고요함과 디지털 기기의 신속함 사이에서, 독서라는 행위 본연의 평온을 지키려는 고민을 드러냅니다.",
                            "Transcending technical specifications, this passage reflects on the ideal conditions for deep human immersion in text—bridging analog tranquility with modern digital agility."
                        ),
                        section2Title = L10n.t("💬 함께 나눌 인문학 토론 질문", "💬 Humanistic Discussion Prompt"),
                        section2Body = L10n.t(
                            "\"디지털 화면의 수많은 자극과 알림 속에서, 온전히 한 권의 책에 몰입하기 위해 독자님에게 가장 필요한 '물리적·심리적 조건'은 무엇인가요?\"",
                            "\"Amid the countless stimuli and notifications of the digital age, what physical and psychological conditions are most essential for you to truly immerse yourself in a book?\""
                        ),
                        promptQuestion = L10n.t("🌿 사유의 질문: 종이의 질감과 디지털 화면 사이에서 우리는 무엇을 얻고 무엇을 잃었을까요?", "🌿 Reflection: Between paper texture and digital glass, what have we gained and what have we lost?"),
                        discussionPrompt = "이 문장은 현대 디지털 기기를 통한 독서 경험의 변화를 다루고 있어. 종이책의 촉각적·아날로그적 고요함과 디지털 매체의 신속함이 현대인의 지식 수용과 사유 방식에 미치는 철학적 영향에 대해 깊이 있는 토론을 나누고 싶어."
                    )
                    isTimeLife -> ModeInsight(
                        badge1 = L10n.t("⏳ 시간과 존재", "⏳ Time & Existence"),
                        badge2 = L10n.t("💡 내면 성찰", "💡 Introspection"),
                        section1Title = L10n.t("✨ 유한성과 실존의 울림", "✨ Resonance of Finitude & Existence"),
                        section1Body = L10n.t(
                            "흘러가는 시간 속에서 인간이 마주하는 고독과 실존적 물음을 응축하고 있습니다. 일상의 안주에서 벗어나 진정한 자아를 발견하려는 결단과 사유의 여정을 독자에게 잔잔한 울림으로 전합니다.",
                            "Captures the existential solitude and quiet yearnings of the human spirit against the relentless passage of time, inviting deep reflection on authenticity."
                        ),
                        section2Title = L10n.t("💬 함께 나눌 인문학 토론 질문", "💬 Humanistic Discussion Prompt"),
                        section2Body = L10n.t(
                            "\"독자님의 삶에서 기존의 안전한 틀을 깨뜨리고 오롯이 자신의 마음에 귀 기울였던 순간이 있으신가요? 이 문장이 가슴에 와닿는 이유는 무엇인가요?\"",
                            "\"Have you ever broken out of a comfortable mold to truly listen to your own inner voice? What makes this quote resonate with your own lived journey?\""
                        ),
                        promptQuestion = L10n.t("🌟 사유의 질문: 영원할 수 없기에 순간이 더 눈부신 이유는 무엇일까요?", "🌟 Reflection: Why does the impossibility of forever make this fleeting moment so radiant?"),
                        discussionPrompt = "이 문장의 문학적·철학적 은유와 인간 실존에 대한 의미를 깊이 있게 해석해주고, 내 생각과 감정을 나누며 1:1 토론을 나누고 싶어."
                    )
                    isEmotionLove -> ModeInsight(
                        badge1 = L10n.t("❤️ 감정의 결", "❤️ Emotional Resonance"),
                        badge2 = L10n.t("🕊️ 관계의 깊이", "🕊️ Depth of Connection"),
                        section1Title = L10n.t("✨ 마음의 온기와 취약성의 힘", "✨ Warmth of Heart & Vulnerability"),
                        section1Body = L10n.t(
                            "타인과의 깊은 교감, 혹은 상실과 그리움이 빚어내는 복합적인 감정선을 섬세하게 포착하고 있습니다. 솔직하게 자신의 취약성을 드러낼 때 비로소 완성되는 인간적 온기를 전합니다.",
                            "Delicately captures the intricate nuances of human connection, longing, and empathy, reminding us of the profound courage found in genuine emotional openness."
                        ),
                        section2Title = L10n.t("💬 함께 나눌 인문학 토론 질문", "💬 Humanistic Discussion Prompt"),
                        section2Body = L10n.t(
                            "\"말로는 온전히 다 표현하지 못했던 누군가를 향한 진심이나, 오래도록 가슴에 맴도는 기억의 한 장면이 떠오르시나요?\"",
                            "\"Does this passage bring to mind a moment of unspoken sincerity toward someone, or an enduring memory etched into your heart?\""
                        ),
                        promptQuestion = L10n.t("💌 사유의 질문: 상처받을 위험에도 불구하고 마음을 여는 용기는 어디에서 오는 걸까요?", "💌 Reflection: Where does the courage to open one's heart come from, despite the risk of pain?"),
                        discussionPrompt = "이 문장에 담긴 인물 간의 정서적 교감과 인간관계의 깊은 심리를 문학적으로 분석해주고, 독자로서 공감할 수 있는 질문을 던져줘."
                    )
                    else -> ModeInsight(
                        badge1 = L10n.t("📖 문학적 은유", "📖 Literary Metaphor"),
                        badge2 = L10n.t("🖋️ 언어의 미학", "🖋️ Poetics of Language"),
                        section1Title = L10n.t("✨ 문장의 결을 따라 걷는 사유", "✨ Contemplation Along the Text"),
                        section1Body = L10n.t(
                            "표면적인 단어 너머에 숨겨진 작가의 시선과 문학적 상징을 드러냅니다. 일상에서 무심코 지나치기 쉬운 감각과 생각을 활자를 통해 다시금 선명하게 일깨워줍니다.",
                            "Illuminates the subtle nuances and symbolic resonance woven between the lines, gently awakening perceptions and sentiments often missed in daily life."
                        ),
                        section2Title = L10n.t("💬 함께 나눌 인문학 토론 질문", "💬 Humanistic Discussion Prompt"),
                        section2Body = L10n.t(
                            "\"이 문장을 소리 내어 읽었을 때 느껴지는 감정의 온도는 어떠신가요? 이 문맥이 독자님에게 던지는 가장 큰 물음은 무엇인가요?\"",
                            "\"What emotional resonance lingers when you read this passage quietly to yourself? What central question does it pose to your own life?\""
                        ),
                        promptQuestion = L10n.t("🌿 사유의 질문: 좋은 글은 독자의 내면에 어떤 파문을 일으킬까요?", "🌿 Reflection: How does authentic writing create ripples within the reader's inner world?"),
                        discussionPrompt = "이 문장의 상징적 표현과 작가의 세계관을 깊이 있게 해석해주고, 삶의 경험과 연결 지어 1:1 대화를 나누고 싶어."
                    )
                }
            }
            GenreMode.HISTORY -> {
                when {
                    isDisplayHardware || isSoftwareArchitecture || isTypography -> ModeInsight(
                        badge1 = L10n.t("🏛️ 기록 매체의 변천사", "🏛️ History of Media"),
                        badge2 = L10n.t("📜 지식 전달의 역사", "📜 Dissemination of Knowledge"),
                        section1Title = L10n.t("🏛️ 파피루스에서 전자종이로 이어진 매체의 진화", "🏛️ From Papyrus to E-Ink: The Media Evolution"),
                        section1Body = L10n.t(
                            "기원전 점토판과 양피지, 15세기 구텐베르크 활판인쇄 혁명, 그리고 1997년 MIT 미디어랩의 전자잉크(E-Ink) 발명에 이르기까지 인류는 '더 가볍고, 눈이 편하며, 지식을 널리 전파할 수 있는 매체'를 끊임없이 탐구해 왔습니다. 이 문장은 종이책의 종말이 아닌, 매체의 현대적 진화 과정을 상징합니다.",
                            "From ancient clay tablets and parchment to Gutenberg's movable type and MIT Media Lab's 1997 invention of E-Ink, humanity has continuously sought lighter, more legible media. This passage marks the modern continuation of that historical arc."
                        ),
                        section2Title = L10n.t("🔗 인과관계 타임라인", "🔗 Cause & Effect Timeline"),
                        section2Body = L10n.t(
                            "• 1455년: 구텐베르크 활판 인쇄술 상용화 → 지식 독점 해체와 르네상스 촉발\n• 1997년: MIT E-Ink 마이크로캡슐 전기영동 기술 개발 → 종이의 광학적 물성을 복제한 디스플레이 탄생\n• 2007년~현재: 고해상도 전자종이와 모바일 OLED의 융합 → 시간과 장소에 구애받지 않는 유비쿼터스 서재 완성",
                            "• 1455: Gutenberg movable type dismantles monopolized knowledge, igniting the Renaissance.\n• 1997: MIT electrophoretic microcapsules replicate the optical physics of ink on paper.\n• 2007-Present: Convergence of high-DPI E-Ink and mobile OLED completes the ubiquitous library."
                        ),
                        promptQuestion = L10n.t("⏳ 역사적 시사점: 매체가 바뀔 때마다 인류의 사고방식은 어떻게 변화해왔을까요?", "⏳ Historical Insight: How has the evolution of reading media reshaped human cognitive habits?"),
                        discussionPrompt = "이 문장에서 언급된 전자종이와 디지털 태블릿은 인류의 지식 기록 및 독서 매체 역사에서 어떤 위치를 차지하는지 설명해줘. 인쇄술의 발명부터 전자책 혁명에 이르기까지 지식의 유통 방식과 독서 문화가 어떻게 변화해왔는지 역사적 맥락과 인과관계를 분석해줘."
                    )
                    isHistoryWar -> ModeInsight(
                        badge1 = L10n.t("🏛️ 시대적 배경", "🏛️ Historical Context"),
                        badge2 = L10n.t("⚔️ 사건의 분기점", "⚔️ Turning Point"),
                        section1Title = L10n.t("🏛️ 사건의 배경과 시대적 인과관계", "🏛️ Underlying Causes & Catalysts"),
                        section1Body = L10n.t(
                            "이 문장은 단순한 단일 사건의 기록이 아니라, 당시 누적된 사회경제적 모순과 정치 권력의 균열이 필연적으로 폭발했던 역사적 분기점을 보여줍니다. 제도적 긴장과 대중의 요구가 맞물려 새로운 질서를 형성하는 과정입니다.",
                            "This statement marks a pivotal inflection point where accumulated socio-economic friction and institutional inertia culminated in systemic transformation."
                        ),
                        section2Title = L10n.t("🔗 역사적 인과관계 사슬", "🔗 Causal Chain of Events"),
                        section2Body = L10n.t(
                            "• 구조적 원인: 장기간 누적된 경제적 격차 및 기존 제도의 경직성\n• 촉발 사건: 결정적 정치적 사건 발발 및 대중 여론의 급격한 결집\n• 역사적 귀결: 기존 권력 구도의 재편과 현대 사회 제도 및 법치 체계로의 계승",
                            "• Structural Cause: Longstanding socio-economic friction and institutional rigidity\n• Catalyst: Pivotal trigger event galvanizing broad public mobilization\n• Lasting Impact: Institutional restructuring laying the groundwork for modern governance"
                        ),
                        promptQuestion = L10n.t("📜 역사의 거울: 당시의 선택이 오늘날 현대 사회에 남긴 가장 뚜렷한 유산은 무엇일까요?", "📜 Mirror of History: What enduring legacy did this historical choice leave for modern society?"),
                        discussionPrompt = "이 역사적 서술의 전후 인과관계와 당시의 정치·사회적 맥락을 타임라인으로 정리해주고, 현대 사회에 던지는 시사점과 교훈을 알려줘."
                    )
                    else -> ModeInsight(
                        badge1 = L10n.t("🏛️ 역사적 맥락", "🏛️ Historical Context"),
                        badge2 = L10n.t("📜 시대 정신", "📜 Zeitgeist"),
                        section1Title = L10n.t("🏛️ 시대를 관통하는 배경과 의미", "🏛️ Historical Background & Meaning"),
                        section1Body = L10n.t(
                            "한 시대의 사회적 통념과 물질적 조건 속에서 형성된 사건과 사상을 조명합니다. 과거의 기록을 통해 현재 우리의 위치를 비추어보고, 제도가 진화해 온 궤적을 이해할 수 있는 단초를 제공합니다.",
                            "Sheds light on the prevailing socio-cultural currents and material conditions of the era, providing valuable perspective on how our present institutions evolved."
                        ),
                        section2Title = L10n.t("🔗 역사적 인과관계 전개", "🔗 Historical Flow & Legacy"),
                        section2Body = L10n.t(
                            "• 배경: 당대 사회가 직면했던 시대적 과제와 지적 풍토\n• 전개: 인물들과 집단의 능동적 결단과 그에 따른 사회적 반향\n• 계승: 오늘날 우리가 누리는 문화와 제도적 기반으로의 발전",
                            "• Context: The defining social challenges and intellectual zeitgeist of the period\n• Development: Decisions made by key figures shaping collective outcomes\n• Legacy: Progressive transmission into modern cultural and institutional frameworks"
                        ),
                        promptQuestion = L10n.t("⏳ 역사의 물음: 과거의 경험을 통해 우리가 오늘날의 변화에 대처할 지혜는 무엇일까요?", "⏳ Historical Inquiry: What wisdom from this past era helps us navigate contemporary challenges?"),
                        discussionPrompt = "이 기록의 시대적 배경과 역사적 인과관계를 설명해주고, 이것이 현대 사회와 인류 문화에 미친 영향에 대해 토론을 나눠줘."
                    )
                }
            }
        }
    }

    /**
     * Executes Google Gemini REST API call asynchronously via pure standard Java HttpURLConnection.
     * Zero external libraries, maintains sub-780KB APK footprint.
     */
    private suspend fun callGeminiRestApi(
        apiKey: String,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        mode: GenreMode,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val model = "gemini-1.5-flash"
        val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true

        val systemInstruction = when (mode) {
            GenreMode.TECH -> "당신은 도서 《$bookTitle》(저자: $bookAuthor, 챕터: $chapterTitle)의 기술/학술 튜터입니다. 인용구: \"$quote\". 비전공자도 이해하기 쉬운 직관적 일상 비유와 3단계 메커니즘으로 명쾌하고 친절하게 한국어로 설명하세요."
            GenreMode.LITERATURE -> "당신은 도서 《$bookTitle》(저자: $bookAuthor, 챕터: $chapterTitle)의 문학/인문학 멘토입니다. 인용구: \"$quote\". 문학적 상징과 은유, 인간 실존에 대한 깊이 있는 통찰을 한국어로 친절하게 나누세요."
            GenreMode.HISTORY -> "당신은 도서 《$bookTitle》(저자: $bookAuthor, 챕터: $chapterTitle)의 역사 멘토입니다. 인용구: \"$quote\". 시대적 배경과 역사적 인과관계를 타임라인과 시사점 위주로 명확히 한국어로 설명하세요."
        }

        val contentsArray = JSONArray()

        // 1. Initial System Context Turn
        val sysTurn = JSONObject().apply {
            put("role", "user")
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", systemInstruction) })
            }
            put("parts", parts)
        }
        contentsArray.put(sysTurn)

        val ackTurn = JSONObject().apply {
            put("role", "model")
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", "네, 《$bookTitle》의 해당 문맥에 맞춰 질문에 성실히 답변하고 유익한 독서 토론을 진행하겠습니다.") })
            }
            put("parts", parts)
        }
        contentsArray.put(ackTurn)

        // 2. Chat history turns
        for (m in history) {
            val turn = JSONObject().apply {
                put("role", if (m.isUser) "user" else "model")
                val parts = JSONArray().apply {
                    put(JSONObject().apply { put("text", m.text) })
                }
                put("parts", parts)
            }
            contentsArray.put(turn)
        }

        val payload = JSONObject().apply {
            put("contents", contentsArray)
            val genConfig = JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 1000)
            }
            put("generationConfig", genConfig)
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code == 200) {
            val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(respStr)
            val candidates = json.getJSONArray("candidates")
            candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
        } else {
            val errStr = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
            val errorMsg = try {
                JSONObject(errStr).getJSONObject("error").getString("message")
            } catch (_: Throwable) {
                errStr
            }
            throw IOException("Gemini API ($code): $errorMsg")
        }
    }

    fun show(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        highlight: HighlightEntity?,
        isLcd: Boolean,
        isDark: Boolean,
        onSaveMemo: ((note: String) -> Unit)? = null,
        onShareQuote: (() -> Unit)? = null
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val app = activity.application as App
        val prefs = app.prefs

        val cleanTitle = bookTitle.ifBlank { L10n.t("제목 미상", "Untitled") }
        val cleanAuthor = bookAuthor.ifBlank { L10n.t("저자 미상", "Unknown Author") }
        val cleanChapter = chapterTitle.ifBlank { L10n.t("본문", "Body") }

        var currentMode = detectGenre(cleanTitle, cleanAuthor, cleanChapter, quote)

        val bgCol = if (isDark) ReaderTheme.DARK_CARD else (if (isLcd) Color.WHITE else ReaderTheme.EINK_PAPER)
        val textCol = if (isDark) ReaderTheme.DARK_TEXT else ReaderTheme.EINK_INK
        val mutedCol = if (isDark) ReaderTheme.DARK_MUTED else ReaderTheme.EINK_MUTED
        val borderCol = if (isDark) ReaderTheme.DARK_BORDER else (if (isLcd) 0xFFDDE3EA.toInt() else ReaderTheme.EINK_BORDER_CONTRAST)
        val accentCol = if (isDark) 0xFF60A5FA.toInt() else ReaderTheme.ACCENT_PRIMARY_BLUE

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCol)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        scroll.addView(root)

        lateinit var dlg: AlertDialog

        // 1. Header (Gemini AI Brand Icon, Title & In-App Status Chip)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val sparkIcon = TextView(activity).apply {
            text = "✨"
            textSize = 20f
            setPadding(0, 0, dp(8), 0)
        }
        val headerTitle = TextView(activity).apply {
            text = if (prefs.geminiApiKey.isNotBlank()) L10n.t("Gemini AI 실시간 대화 (In-App)", "Gemini AI Live Chat (In-App)")
                   else L10n.t("Gemini AI 독서 어시스턴트", "Gemini AI Reading Companion")
            textSize = 17f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(textCol)
        }
        headerRow.addView(sparkIcon)
        headerRow.addView(headerTitle)
        root.addView(headerRow)

        // 2. Book metadata chip
        val bookChip = TextView(activity).apply {
            text = "📖 《$cleanTitle》 ($cleanAuthor) · $cleanChapter"
            textSize = 12f
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(bookChip)

        // 3. Genre Mode Switcher Tabs
        val tabContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }

        val tabs = listOf(
            GenreMode.LITERATURE to L10n.t("📖 문학·사유", "📖 Literary"),
            GenreMode.TECH to L10n.t("⚙️ 교과서·기술 튜터", "⚙️ Tech Tutor"),
            GenreMode.HISTORY to L10n.t("🏛️ 역사·인과관계", "🏛️ History")
        )

        val tabViews = ArrayList<TextView>()
        val contentBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // In-App Chat Conversation History
        val conversationHistory = ArrayList<ChatMessage>()

        fun updateTabsAndContent() {
            tabViews.forEachIndexed { i, tv ->
                val (mode, _) = tabs[i]
                val isSelected = mode == currentMode
                if (isSelected) {
                    tv.background = cardDrawable(if (isDark) 0xFF1E3A8A.toInt() else 0xFFE0E7FF.toInt(), dp(14), accentCol, dp(1))
                    tv.setTextColor(if (isDark) Color.WHITE else 0xFF1E40AF.toInt())
                    tv.typeface = Typeface.DEFAULT_BOLD
                } else {
                    tv.background = cardDrawable(Color.TRANSPARENT, dp(14), borderCol, dp(1))
                    tv.setTextColor(mutedCol)
                    tv.typeface = Typeface.DEFAULT
                }
            }

            contentBox.removeAllViews()

            // Quote Card
            val quoteCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardDrawable(if (isDark) 0xFF1F2937.toInt() else 0xFFF8FAFC.toInt(), dp(8), borderCol, dp(1))
            }
            val quoteTv = TextView(activity).apply {
                text = "“$quote”"
                textSize = 14f
                setLineSpacing(0f, 1.35f)
                typeface = Typeface.create("serif", Typeface.ITALIC)
                setTextColor(textCol)
            }
            quoteCard.addView(quoteTv)
            contentBox.addView(quoteCard)

            val spacer1 = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
            contentBox.addView(spacer1)

            val insight = generateInsight(currentMode, cleanTitle, cleanAuthor, cleanChapter, quote)

            val apiKey = prefs.geminiApiKey.trim()
            val isInAppChatActive = apiKey.isNotBlank()

            if (isInAppChatActive) {
                // ==============================================================
                // 100% IN-APP LIVE CONVERSATIONAL CHAT MODE
                // ==============================================================
                // Compact initial summary badge
                val summaryCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = cardDrawable(if (isDark) 0xFF0F172A.toInt() else 0xFFF0FDF4.toInt(), dp(8), 0xFF22C55E.toInt(), dp(1))
                }
                val summaryTitle = TextView(activity).apply {
                    text = "${insight.badge1} · ${insight.section1Title}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isDark) 0xFF86EFAC.toInt() else 0xFF15803D.toInt())
                }
                val summaryBody = TextView(activity).apply {
                    text = insight.section1Body
                    textSize = 12f
                    setLineSpacing(0f, 1.25f)
                    setTextColor(if (isDark) 0xFFCBD5E1.toInt() else 0xFF166534.toInt())
                }
                summaryCard.addView(summaryTitle)
                summaryCard.addView(summaryBody)
                contentBox.addView(summaryCard)

                val spacerChat = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
                contentBox.addView(spacerChat)

                // Chat Messages Container
                val chatMessagesBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                fun renderBubbles() {
                    chatMessagesBox.removeAllViews()
                    for (msg in conversationHistory) {
                        val bubbleRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = if (msg.isUser) Gravity.END else Gravity.START
                            setPadding(0, dp(4), 0, dp(4))
                        }
                        val bubble = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(9), dp(12), dp(9))
                            background = if (msg.isUser) {
                                cardDrawable(0xFF2563EB.toInt(), dp(12))
                            } else {
                                cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFEFF6FF.toInt(), dp(12), if (isDark) 0xFF334155.toInt() else 0xFFBFDBFE.toInt(), dp(1))
                            }
                        }
                        val authorTag = TextView(activity).apply {
                            text = if (msg.isUser) L10n.t("👤 나 (독자)", "👤 Me") else L10n.t("🤖 Gemini AI", "🤖 Gemini AI")
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (msg.isUser) 0xFFBFDBFE.toInt() else (if (isDark) 0xFF93C5FD.toInt() else 0xFF1D4ED8.toInt()))
                            setPadding(0, 0, 0, dp(3))
                        }
                        val textTv = TextView(activity).apply {
                            text = msg.text
                            textSize = 13f
                            setLineSpacing(0f, 1.3f)
                            setTextColor(if (msg.isUser) Color.WHITE else textCol)
                        }
                        bubble.addView(authorTag)
                        bubble.addView(textTv)

                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (msg.isUser) leftMargin = dp(40) else rightMargin = dp(40)
                        }
                        bubbleRow.addView(bubble, lp)
                        chatMessagesBox.addView(bubbleRow)
                    }
                }

                // Initial welcome message from Gemini if empty
                if (conversationHistory.isEmpty()) {
                    val initialGreeting = when (currentMode) {
                        GenreMode.TECH -> L10n.t(
                            "안녕하세요! 《$cleanTitle》에서 선택하신 문장의 동작 원리나 일상 비유, 기기별 최적화에 대해 무엇이든 편하게 물어보세요.",
                            "Hello! Feel free to ask anything about the mechanisms, analogies, or device optimizations for this quote from 《$cleanTitle》."
                        )
                        GenreMode.LITERATURE -> L10n.t(
                            "안녕하세요! 이 문장의 상징적 은유나 인물의 내면적 갈등에 대해 1:1로 깊이 있는 사유와 토론을 나누어 보세요.",
                            "Hello! Let's reflect together on the literary symbolism, metaphors, and existential themes in this passage."
                        )
                        GenreMode.HISTORY -> L10n.t(
                            "안녕하세요! 이 역사적 서술의 시대적 배경이나 전후 인과관계, 현대적 시사점에 대해 궁금한 점을 질문해주세요.",
                            "Hello! Ask me about the historical background, cause-and-effect timeline, or modern implications of this statement."
                        )
                    }
                    conversationHistory.add(ChatMessage("model", initialGreeting, false))
                }
                renderBubbles()
                contentBox.addView(chatMessagesBox)

                // Quick Suggestion Chips (Horizontal Scroll)
                val chipScroll = HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, dp(8), 0, dp(8))
                }
                val chipBox = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

                val suggestions = when (currentMode) {
                    GenreMode.TECH -> listOf(
                        L10n.t("💡 더 쉬운 일상 비유로 설명해줘", "💡 Explain with simpler everyday analogy"),
                        L10n.t("⚙️ 3단계 동작 메커니즘 도식화", "⚙️ Breakdown into 3-step mechanism"),
                        L10n.t("🔋 배터리 소모와 성능 차이는?", "🔋 Battery drain & performance impact?"),
                        L10n.t("📝 핵심 내용 3줄 요약", "📝 3-line summary")
                    )
                    GenreMode.LITERATURE -> listOf(
                        L10n.t("🖋️ 작가의 세계관과 숨은 은유 분석", "🖋️ Analyze author's worldview & metaphor"),
                        L10n.t("💭 나에게 적용해볼 인문학 질문", "💭 Humanistic reflection prompt"),
                        L10n.t("🌿 다른 관점의 비판적 시각", "🌿 Critical counter-perspective"),
                        L10n.t("📝 핵심 사유 3줄 정리", "📝 3-line philosophical takeaway")
                    )
                    GenreMode.HISTORY -> listOf(
                        L10n.t("⏳ 사건의 전후 인과관계 요약", "⏳ Cause & effect timeline summary"),
                        L10n.t("📜 현대 사회에 던지는 교훈", "📜 Lessons for contemporary society"),
                        L10n.t("⚔️ 당시의 결정적 분기점", "⚔️ Decisive historical turning point"),
                        L10n.t("📝 역사적 의의 3줄 정리", "📝 3-line historical significance")
                    )
                }

                fun executePrompt(userQuery: String) {
                    if (userQuery.isBlank()) return
                    conversationHistory.add(ChatMessage("user", userQuery, true))
                    renderBubbles()

                    val loadingRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    val loadingBubble = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt(), dp(12))
                    }
                    val loadingTv = TextView(activity).apply {
                        text = L10n.t("🤖 Gemini AI가 답변을 작성하고 있습니다... ✨", "🤖 Gemini AI is thinking... ✨")
                        textSize = 12f
                        setTextColor(mutedCol)
                    }
                    loadingBubble.addView(loadingTv)
                    loadingRow.addView(loadingBubble)
                    chatMessagesBox.addView(loadingRow)
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val reply = callGeminiRestApi(apiKey, cleanTitle, cleanAuthor, cleanChapter, quote, currentMode, conversationHistory)
                            chatMessagesBox.removeView(loadingRow)
                            conversationHistory.add(ChatMessage("model", reply, false))
                            renderBubbles()
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        } catch (e: Throwable) {
                            chatMessagesBox.removeView(loadingRow)
                            val errorMsg = L10n.t(
                                "⚠️ Gemini API 응답 오류: ${e.message}\n(API Key를 확인하시거나 하단의 [외부 Gemini 앱으로 열기]를 이용해 주세요.)",
                                "⚠️ Gemini API Error: ${e.message}\n(Please verify your API key or use [Open in External Gemini App] below.)"
                            )
                            conversationHistory.add(ChatMessage("model", errorMsg, false))
                            renderBubbles()
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }

                for (sug in suggestions) {
                    val chipBtn = TextView(activity).apply {
                        text = sug
                        textSize = 11.5f
                        setTextColor(accentCol)
                        background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt(), dp(12), accentCol, dp(1))
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        setOnClickListener {
                            executePrompt(sug)
                        }
                    }
                    val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    }
                    chipBox.addView(chipBtn, clp)
                }
                chipScroll.addView(chipBox)
                contentBox.addView(chipScroll)

                // Input Box Row
                val inputRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(4))
                }
                val inputEt = EditText(activity).apply {
                    hint = L10n.t("Gemini에게 질문하거나 생각을 적어보세요...", "Ask Gemini a question or share thoughts...")
                    textSize = 12.5f
                    setTextColor(textCol)
                    setHintTextColor(mutedCol)
                    background = cardDrawable(if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF8FAFC.toInt(), dp(20), borderCol, dp(1))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                val sendBtn = TextView(activity).apply {
                    text = L10n.t("전송 🚀", "Send 🚀")
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = cardDrawable(0xFF2563EB.toInt(), dp(20))
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener {
                        val txt = inputEt.text.toString().trim()
                        if (txt.isNotBlank()) {
                            inputEt.text.clear()
                            executePrompt(txt)
                        }
                    }
                }
                inputRow.addView(inputEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                inputRow.addView(sendBtn)
                contentBox.addView(inputRow)

            } else {
                // ==============================================================
                // STANDARD ANALYSIS MODE (External App Fallback / Key Not Set)
                // ==============================================================
                // Badge Row
                val badgeRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(6))
                }
                val b1 = TextView(activity).apply {
                    text = insight.badge1
                    textSize = 11f
                    setTextColor(if (isDark) 0xFF86EFAC.toInt() else 0xFF14532D.toInt())
                    background = cardDrawable(if (isDark) 0xFF052E16.toInt() else 0xFFDCFCE7.toInt(), dp(4))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                val b2 = TextView(activity).apply {
                    text = insight.badge2
                    textSize = 11f
                    setTextColor(if (isDark) 0xFF93C5FD.toInt() else 0xFF1E40AF.toInt())
                    background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFDBEAFE.toInt(), dp(4))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
                }
                badgeRow.addView(b1)
                badgeRow.addView(b2)
                contentBox.addView(badgeRow)

                // Section 1: Core Insight / Analogy
                val s1Title = TextView(activity).apply {
                    text = insight.section1Title
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textCol)
                    setPadding(0, dp(4), 0, dp(4))
                }
                val s1Body = TextView(activity).apply {
                    text = insight.section1Body
                    textSize = 13f
                    setLineSpacing(0f, 1.35f)
                    setTextColor(textCol)
                    setPadding(0, 0, 0, dp(10))
                }
                contentBox.addView(s1Title)
                contentBox.addView(s1Body)

                // Section 2: Mechanism / Timeline
                val s2Box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = cardDrawable(if (isDark) 0xFF1F2937.toInt() else 0xFFF1F5F9.toInt(), dp(8), borderCol, dp(1))
                }
                val s2Title = TextView(activity).apply {
                    text = insight.section2Title
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textCol)
                    setPadding(0, 0, 0, dp(4))
                }
                val s2Body = TextView(activity).apply {
                    text = insight.section2Body
                    textSize = 12f
                    setLineSpacing(0f, 1.3f)
                    setTextColor(mutedCol)
                }
                s2Box.addView(s2Title)
                s2Box.addView(s2Body)
                contentBox.addView(s2Box)

                if (insight.promptQuestion.isNotBlank()) {
                    val spacerQuiz = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) }
                    contentBox.addView(spacerQuiz)

                    val quizBox = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = cardDrawable(if (isDark) 0xFF172554.toInt() else 0xFFEFF6FF.toInt(), dp(8), 0xFF3B82F6.toInt(), dp(1))
                    }
                    val quizTv = TextView(activity).apply {
                        text = insight.promptQuestion
                        textSize = 11.5f
                        setTextColor(if (isDark) 0xFF93C5FD.toInt() else 0xFF1D4ED8.toInt())
                    }
                    quizBox.addView(quizTv)
                    contentBox.addView(quizBox)
                }

                // In-App Chat Unlock Card
                val unlockCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = cardDrawable(if (isDark) 0xFF1E293B.toInt() else 0xFFF8FAFC.toInt(), dp(8), borderCol, dp(1))
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
                }
                val unlockTv = TextView(activity).apply {
                    text = L10n.t("💡 무료 API Key 등록 시 독서 화면을 벗어나지 않고 팝업에서 바로 1:1 대화가 가능합니다.", "💡 Register a free API key to enable 1:1 live chat directly inside this in-app popup.")
                    textSize = 11.5f
                    setTextColor(mutedCol)
                }
                val unlockBtn = TextView(activity).apply {
                    text = L10n.t("키 등록", "Set Key")
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(accentCol)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        activity.inputDialog(
                            title = L10n.t("Google Gemini API Key 입력", "Enter Gemini API Key"),
                            initial = prefs.geminiApiKey
                        ) { newKey ->
                            prefs.geminiApiKey = newKey.trim()
                            updateTabsAndContent()
                        }
                    }
                }
                unlockCard.addView(unlockTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                unlockCard.addView(unlockBtn)
                contentBox.addView(unlockCard)
            }
        }

        // Build Tabs
        for (idx in tabs.indices) {
            val (mode, label) = tabs[idx]
            val tv = TextView(activity).apply {
                text = label
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (currentMode != mode) {
                        currentMode = mode
                        conversationHistory.clear()
                        updateTabsAndContent()
                    }
                }
            }
            tabViews.add(tv)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(6)
            }
            tabContainer.addView(tv, lp)
        }
        root.addView(tabContainer)
        root.addView(contentBox)

        // 4. Action Buttons (Launch Gemini App, Save Note, Share SNS, Close)
        val actionDivider = View(activity).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        }
        root.addView(actionDivider)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val hasApiKey = prefs.geminiApiKey.isNotBlank()

        if (!hasApiKey) {
            // Primary Button when no API key: Launch Gemini App with Auto-Prefill
            val launchGeminiBtn = activity.pillButton(
                L10n.t("🚀 Gemini 앱에서 1:1 토론 시작", "🚀 Continue 1:1 in Gemini"),
                Color.WHITE,
                0xFF2563EB.toInt(),
                0xFF1D4ED8.toInt()
            ) {
                dlg.dismiss()
                val currentInsight = generateInsight(currentMode, cleanTitle, cleanAuthor, cleanChapter, quote)
                openGeminiWithPrompt(activity, cleanTitle, cleanAuthor, cleanChapter, quote, currentMode, currentInsight.discussionPrompt)
            }
            btnRow.addView(launchGeminiBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val subBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, if (!hasApiKey) dp(8) else 0, 0, 0)
        }

        // Save AI insight / conversation to Note
        val saveMemoBtn = activity.pillButton(
            L10n.t("📝 메모로 저장", "📝 Save to Note"),
            textCol,
            if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
            borderCol
        ) {
            val curInsight = generateInsight(currentMode, cleanTitle, cleanAuthor, cleanChapter, quote)
            val insightSummary = if (conversationHistory.size > 1) {
                val sb = StringBuilder()
                sb.append("[Gemini 1:1 토론 기록]\n")
                sb.append("📖 《$cleanTitle》 ($cleanAuthor)\n")
                sb.append("“$quote”\n\n")
                for (m in conversationHistory) {
                    if (m.isUser) sb.append("👤 질문: ${m.text}\n")
                    else sb.append("🤖 Gemini: ${m.text}\n\n")
                }
                sb.toString().trim()
            } else {
                when (currentMode) {
                    GenreMode.LITERATURE -> "[Gemini 문학 성찰] $quote\n\n${curInsight.section1Body}\n\n${curInsight.section2Body}"
                    GenreMode.TECH -> "[Gemini 기술 요약] $quote\n\n${curInsight.section1Body}\n\n${curInsight.section2Body}"
                    GenreMode.HISTORY -> "[Gemini 역사 인과] $quote\n\n${curInsight.section1Body}\n\n${curInsight.section2Body}"
                }
            }
            onSaveMemo?.invoke(insightSummary)
            Toast.makeText(activity, L10n.t("Gemini 대화 및 인사이트가 독서 메모로 저장되었습니다.", "Gemini conversation saved to reading notes."), Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        subBtnRow.addView(saveMemoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val subSpacer1 = View(activity).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) }
        subBtnRow.addView(subSpacer1)

        // Share to SNS card
        val shareBtn = activity.pillButton(
            L10n.t("📢 카드 공유", "📢 Share Card"),
            textCol,
            if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
            borderCol
        ) {
            dlg.dismiss()
            onShareQuote?.invoke()
        }
        subBtnRow.addView(shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (hasApiKey) {
            val subSpacer2 = View(activity).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) }
            subBtnRow.addView(subSpacer2)

            // External App Fallback Button
            val extBtn = activity.pillButton(
                L10n.t("🚀 외부 앱 열기", "🚀 External"),
                mutedCol,
                if (isDark) ReaderTheme.DARK_SURFACE else 0xFFF1F5F9.toInt(),
                borderCol
            ) {
                dlg.dismiss()
                val currentInsight = generateInsight(currentMode, cleanTitle, cleanAuthor, cleanChapter, quote)
                openGeminiWithPrompt(activity, cleanTitle, cleanAuthor, cleanChapter, quote, currentMode, currentInsight.discussionPrompt)
            }
            subBtnRow.addView(extBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        }

        btnRow.addView(subBtnRow)
        root.addView(btnRow)

        // Initial content population
        updateTabsAndContent()

        dlg = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(activity, enforceBalancedMargins = true)
    }

    private fun openGeminiWithPrompt(
        activity: Activity,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        quote: String,
        mode: GenreMode,
        customInstruction: String? = null
    ) {
        val modePrompt = customInstruction ?: when (mode) {
            GenreMode.LITERATURE -> "이 문학 문장의 상징적 은유와 작가의 세계관을 깊이 있게 해석해주고, 내 생각과 감정을 나누며 1:1 토론을 나누고 싶어."
            GenreMode.TECH -> "이 기술/학술 개념을 비전공자도 3초 만에 이해할 수 있는 일상 비유와 3단계 메커니즘으로 분해해서 설명해주고 관련 개념 퀴즈를 내줘."
            GenreMode.HISTORY -> "이 역사적 서술의 전후 인과관계와 당시의 정치·사회적 맥락을 타임라인으로 정리해주고 현대적 교훈을 알려줘."
        }

        val prompt = """
[도서 정보]
- 도서명: 《$bookTitle》
- 저자: $bookAuthor
- 챕터: $chapterTitle

[선택한 본문 문장]
"$quote"

[토론 요청]
$modePrompt
""".trimIndent()

        // 1. Always copy prompt to system clipboard so user never loses it on any platform
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Prompt", prompt))
            }
        } catch (_: Throwable) {}

        val encodedQuery = try {
            URLEncoder.encode(prompt, "UTF-8")
        } catch (_: Throwable) {
            Uri.encode(prompt)
        }
        val webUri = Uri.parse("https://gemini.google.com/app?q=$encodedQuery")
        val pm = activity.packageManager

        val geminiPackage = "com.google.android.apps.bard"
        val googlePackage = "com.google.android.googlequicksearchbox"

        // Strategy 1 (Top Priority): Intent.ACTION_SEND to Gemini app
        // Passes prompt via Intent.EXTRA_TEXT and ClipData so Gemini automatically pre-fills it in the input field!
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra(Intent.EXTRA_SUBJECT, "《$bookTitle》 독서 토론")
                clipData = ClipData.newPlainText("Gemini Prompt", prompt)
                setPackage(geminiPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val matches = pm.queryIntentActivities(sendIntent, 0)
            if (matches.isNotEmpty()) {
                if (matches.size == 1) {
                    val ai = matches[0].activityInfo
                    sendIntent.component = ComponentName(ai.packageName, ai.name)
                }
                activity.startActivity(sendIntent)
                Toast.makeText(
                    activity,
                    L10n.t("✨ Gemini 대화창에 프롬프트를 자동으로 입력했습니다.", "✨ Prompt automatically prefilled in Gemini!"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        } catch (_: Throwable) {}

        // Strategy 2: Intent.ACTION_VIEW with gemini.google.com targeting Gemini package
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                setPackage(geminiPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (viewIntent.resolveActivity(pm) != null || pm.queryIntentActivities(viewIntent, 0).isNotEmpty()) {
                activity.startActivity(viewIntent)
                Toast.makeText(activity, L10n.t("✨ Gemini 대화창으로 연결합니다. (프롬프트 복사됨)", "✨ Opening Gemini... (Prompt copied)"), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Throwable) {}

        // Strategy 3: Google Assistant / Search App (com.google.android.googlequicksearchbox) with ACTION_SEND
        try {
            val isGoogleInstalled = pm.getPackageInfo(googlePackage, 0) != null
            if (isGoogleInstalled) {
                val sendGoogle = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, prompt)
                    putExtra(Intent.EXTRA_SUBJECT, "《$bookTitle》 독서 토론")
                    clipData = ClipData.newPlainText("Gemini Prompt", prompt)
                    setPackage(googlePackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val matches = pm.queryIntentActivities(sendGoogle, 0)
                if (matches.isNotEmpty()) {
                    if (matches.size == 1) {
                        val ai = matches[0].activityInfo
                        sendGoogle.component = ComponentName(ai.packageName, ai.name)
                    }
                    activity.startActivity(sendGoogle)
                    Toast.makeText(activity, L10n.t("✨ Google 어시스턴트/Gemini 대화창에 자동 입력했습니다.", "✨ Prefilled in Google Assistant/Gemini!"), Toast.LENGTH_SHORT).show()
                    return
                }
            }
        } catch (_: Throwable) {}

        // Strategy 4: Fallback to getLaunchIntentForPackage if ACTION_SEND was not exported
        val launchIntent = try {
            pm.getLaunchIntentForPackage(geminiPackage)
        } catch (_: Throwable) {
            null
        }
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.putExtra(Intent.EXTRA_TEXT, prompt)
                launchIntent.clipData = ClipData.newPlainText("Gemini Prompt", prompt)
                activity.startActivity(launchIntent)
                Toast.makeText(
                    activity,
                    L10n.t("✨ Gemini 앱을 실행했습니다! 대화창에 바로 붙여넣으세요. (복사 완료)", "✨ Gemini app opened! Paste prompt into chat. (Copied)"),
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Throwable) {}
        }

        // Strategy 5: Fallback to default web browser (https://gemini.google.com/app?q=...)
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(browserIntent)
            Toast.makeText(activity, L10n.t("✨ Gemini 웹으로 연결합니다. (프롬프트 복사됨)", "✨ Opening Gemini web... (Prompt copied)"), Toast.LENGTH_SHORT).show()
            return
        } catch (_: Throwable) {}

        // Strategy 6: General Chooser Fallback (select any AI / chat app)
        try {
            val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                clipData = ClipData.newPlainText("Gemini Prompt", prompt)
            }, L10n.t("Gemini 또는 대화할 AI 앱 선택", "Select Gemini or AI App")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(chooser)
        } catch (e: Throwable) {
            Toast.makeText(activity, L10n.t("Gemini 앱/브라우저를 열 수 없습니다: ${e.message}", "Unable to launch Gemini: ${e.message}"), Toast.LENGTH_SHORT).show()
        }
    }
}
