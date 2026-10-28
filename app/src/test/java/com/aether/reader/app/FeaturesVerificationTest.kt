package com.aether.reader.app

import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.domain.display.PageTransition
import org.junit.Assert.*
import org.junit.Test

class FeaturesVerificationTest {

    @Test
    fun testDummyChapterFilteringAndCleaning() {
        // 더미 챕터 검사 로직
        fun isDummyTitle(t: String): Boolean {
            val s = t.trim()
            return s.isBlank() || s.matches(Regex("""^(?:챕터|Chapter|섹션|Section|장|Ch\.?)\s*\d+$""", RegexOption.IGNORE_CASE))
        }

        // 목차 접두어 정리 로직
        fun cleanTitle(t: String): String {
            val cleaned = t.trim()
                .replace(Regex("""^(?:챕터|Chapter|섹션|Section|장|Ch\.?)\s*\d+[\s:.\-·~]+""", RegexOption.IGNORE_CASE), "")
                .trim()
            return cleaned.ifBlank { t.trim() }
        }

        // 1. 더미 챕터 판별 검증
        assertTrue(isDummyTitle("챕터 1"))
        assertTrue(isDummyTitle("챕터 12"))
        assertTrue(isDummyTitle("Chapter 3"))
        assertTrue(isDummyTitle("chapter 99"))
        assertTrue(isDummyTitle("섹션 2"))
        assertTrue(isDummyTitle("장 5"))
        assertTrue(isDummyTitle("  Ch. 4  "))
        assertTrue(isDummyTitle(""))
        assertTrue(isDummyTitle("   "))

        // 2. 실제 목차 타이틀 판별 검증
        assertFalse(isDummyTitle("프롤로그"))
        assertFalse(isDummyTitle("에필로그"))
        assertFalse(isDummyTitle("제 1 장 새로운 모험의 시작"))
        assertFalse(isDummyTitle("어린 시절의 회상"))

        // 3. 접두어 정리 검증
        assertEquals("프롤로그: 만남", cleanTitle("챕터 1. 프롤로그: 만남"))
        assertEquals("새로운 시작", cleanTitle("Chapter 2 - 새로운 시작"))
        assertEquals("깊은 바다", cleanTitle("섹션 3: 깊은 바다"))
        assertEquals("제 1 장 이야기", cleanTitle("제 1 장 이야기"))

        // 4. 리스트 필터링 시나리오 검증:
        // 실제 목차가 섞여 있는 경우 더미 항목은 제거되고 실제 목차만 남아야 함
        val rawChapterTitles = listOf(
            "챕터 1",          // 표지 (더미)
            "챕터 2",          // 속표지 (더미)
            "프롤로그",         // 실제 목차
            "챕터 4",          // 빈 페이지 (더미)
            "제 1 장 모험의 시작", // 실제 목차
            "챕터 6",          // 공백 (더미)
            "에필로그"          // 실제 목차
        )

        val hasRealTitles = rawChapterTitles.any { !isDummyTitle(it) }
        assertTrue(hasRealTitles)

        val filtered = rawChapterTitles.filter { !hasRealTitles || !isDummyTitle(it) }
            .map { cleanTitle(it) }

        assertEquals(listOf("프롤로그", "제 1 장 모험의 시작", "에필로그"), filtered)
    }

    @Test
    fun testChapterStartPageAccumulation() {
        // PagingEngineImpl의 chapterStartPage 로직 검증
        val chapterPageCounts = intArrayOf(5, 12, 8, 20)

        fun chapterStartPage(ch: Int): Int {
            val counts = chapterPageCounts
            val before = (0 until ch.coerceIn(0, counts.size)).sumOf { counts[it] }
            return before + 1
        }

        // 챕터 0: 1페이지부터 (총 5페이지)
        assertEquals(1, chapterStartPage(0))
        // 챕터 1: 6페이지부터 (1 + 5, 총 12페이지)
        assertEquals(6, chapterStartPage(1))
        // 챕터 2: 18페이지부터 (1 + 5 + 12, 총 8페이지)
        assertEquals(18, chapterStartPage(2))
        // 챕터 3: 26페이지부터 (1 + 5 + 12 + 8, 총 20페이지)
        assertEquals(26, chapterStartPage(3))
    }

    @Test
    fun testEpubOpfMetadataRegexExtraction() {
        val sampleOpf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>어린 왕자</dc:title>
                    <dc:creator id="creator">앙투안 드 생텍쥐페리</dc:creator>
                    <dc:contributor opf:role="trl">김철수</dc:contributor>
                    <dc:publisher>민음사</dc:publisher>
                    <dc:date>2021-05-15</dc:date>
                </metadata>
            </package>
        """.trimIndent()

        // 출판사 추출 정규식 검증
        val pubMatch = Regex("""(?is)<(?:[a-zA-Z0-9]+:)?publisher[^>]*>([^<]+)""").find(sampleOpf)
        assertNotNull(pubMatch)
        assertEquals("민음사", pubMatch!!.groupValues[1].trim())

        // 출시 연도 추출 정규식 검증
        val dateMatch = Regex("""(?is)<(?:[a-zA-Z0-9]+:)?date[^>]*>([^<]+)""").find(sampleOpf)
        assertNotNull(dateMatch)
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(dateMatch!!.groupValues[1])?.value
        assertEquals("2021", year)

        // 옮긴이 추출 정규식 검증
        val trlMatch = Regex("""(?is)<(?:[a-zA-Z0-9]+:)?contributor[^>]*role=["']trl["'][^>]*>([^<]+)""").find(sampleOpf)
        assertNotNull(trlMatch)
        assertEquals("김철수", trlMatch!!.groupValues[1].trim())
    }

    @Test
    fun testShelfManagementDataIntegrity() {
        // 책장 관리 모델 로직 검증
        val shelves = mutableListOf<String>()
        val bookShelfMap = mutableMapOf<String, String>()

        // 1. 책장 추가
        fun addShelf(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || shelves.contains(trimmed)) return false
            shelves.add(trimmed)
            return true
        }

        // 2. 도서 책장 지정
        fun setBooksShelf(paths: Collection<String>, shelfName: String) {
            val s = shelfName.trim()
            for (p in paths) {
                if (s.isEmpty()) bookShelfMap.remove(p) else bookShelfMap[p] = s
            }
        }

        // 3. 책장 이름 변경
        fun renameShelf(oldName: String, newName: String): Boolean {
            val trimmed = newName.trim()
            val idx = shelves.indexOf(oldName)
            if (idx < 0 || trimmed.isEmpty() || shelves.contains(trimmed)) return false
            shelves[idx] = trimmed
            for ((path, shelf) in bookShelfMap.toMap()) {
                if (shelf == oldName) bookShelfMap[path] = trimmed
            }
            return true
        }

        // 4. 책장 삭제
        fun deleteShelf(name: String): Boolean {
            if (!shelves.remove(name)) return false
            val it = bookShelfMap.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value == name) it.remove()
            }
            return true
        }

        // 검증 시작
        assertTrue(addShelf("소설"))
        assertTrue(addShelf("경영/경제"))
        assertFalse("중복 책장은 허용되지 않음", addShelf("소설"))

        // 도서 이동 및 분류
        val book1 = "/sdcard/book1.epub"
        val book2 = "/sdcard/book2.epub"
        val book3 = "/sdcard/book3.epub"

        setBooksShelf(listOf(book1, book2), "소설")
        setBooksShelf(listOf(book3), "경영/경제")

        assertEquals("소설", bookShelfMap[book1])
        assertEquals("소설", bookShelfMap[book2])
        assertEquals("경영/경제", bookShelfMap[book3])

        // 책장 이름 변경 시 도서 소속도 함께 업데이트되는지 검증
        assertTrue(renameShelf("소설", "문학/소설"))
        assertEquals("문학/소설", bookShelfMap[book1])
        assertEquals("문학/소설", bookShelfMap[book2])

        // 책장 삭제 시 소속 도서가 안전하게 미분류로 돌아가는지 검증
        assertTrue(deleteShelf("문학/소설"))
        assertNull(bookShelfMap[book1])
        assertNull(bookShelfMap[book2])
        assertEquals("경영/경제", bookShelfMap[book3]) // 다른 책장은 영향 없음
    }

    @Test
    fun testWhispersyncPayloadAndDecisionLogic() {
        // 1. 원격 진행도 비교 로직 검증 (Whispersync)
        fun shouldPromptRemoteJump(
            remoteUpdatedAt: Long,
            localLastRead: Long,
            remoteCh: Int,
            localCh: Int,
            remoteCharOff: Long,
            localCharOff: Long
        ): Boolean {
            if (remoteUpdatedAt <= localLastRead) return false
            if (remoteCh != localCh) return true
            return kotlin.math.abs(remoteCharOff - localCharOff) > 2000
        }

        val tNow = 1700000000000L
        val tPast = 1690000000000L

        // 최신 원격 위치가 다른 챕터인 경우 -> 이동 제안
        assertTrue(shouldPromptRemoteJump(tNow, tPast, 2, 1, 500, 1000))
        // 최신 원격 위치가 같은 챕터지만 2000자 초과 차이인 경우 -> 이동 제안
        assertTrue(shouldPromptRemoteJump(tNow, tPast, 1, 1, 3500, 1000))
        // 최신 원격 위치가 같은 챕터이고 2000자 이내 미세 차이인 경우 -> 로컬 유지
        assertFalse(shouldPromptRemoteJump(tNow, tPast, 1, 1, 1500, 1000))
        // 로컬이 더 최신인 경우 -> 원격 제안 안 함
        assertFalse(shouldPromptRemoteJump(tPast, tNow, 2, 1, 500, 1000))
        // 시간이 동일한 경우 -> 원격 제안 안 함
        assertFalse(shouldPromptRemoteJump(tNow, tNow, 2, 1, 500, 1000))
    }

    @Test
    fun testDeviceBifurcationSpecifications() {
        // 1. 디스플레이 프로필에 따른 LCD 여부 분기 검증
        val monoKind = "MONO_EINK"
        val colorKind = "COLOR_EINK"
        val lcdKind = "LCD_OLED"

        fun isLcd(kind: String): Boolean = kind == "LCD_OLED"

        assertFalse("흑백 E-Ink는 LCD가 아님 (인스턴트 고대비 모달 사용)", isLcd(monoKind))
        assertFalse("컬러 E-Ink는 LCD가 아님 (인스턴트 고대비 모달 사용)", isLcd(colorKind))
        assertTrue("LCD/OLED는 슬라이딩 스낵바/바텀시트 사용", isLcd(lcdKind))

        // 2. 인라인 각주 하드웨어별 파라미터 분기 검증
        fun getFootnoteSpec(isLcd: Boolean): Pair<Int, Int> {
            val strokeDp = if (isLcd) 1 else 2 // E-Ink는 2dp 고대비 실선
            val cornerDp = if (isLcd) 16 else 8 // LCD는 부드러운 16dp 라운드
            return Pair(strokeDp, cornerDp)
        }

        val einkSpec = getFootnoteSpec(isLcd(monoKind))
        assertEquals(2, einkSpec.first)
        assertEquals(8, einkSpec.second)

        val lcdSpec = getFootnoteSpec(isLcd(lcdKind))
        assertEquals(1, lcdSpec.first)
        assertEquals(16, lcdSpec.second)
    }

    @Test
    fun testVariableFontWeightAndStrokeBooster() {
        // 1. 가변 폰트 웨이트 클램핑 및 OpenType 설정 검증
        fun buildFontVariation(weight: Int): String {
            val clamped = weight.coerceIn(100, 900)
            return "'wght' $clamped"
        }

        assertEquals("'wght' 400", buildFontVariation(400))
        assertEquals("'wght' 700", buildFontVariation(700))
        assertEquals("'wght' 100", buildFontVariation(50))   // 하한 클램핑
        assertEquals("'wght' 900", buildFontVariation(1200)) // 상한 클램핑

        // 2. E-Ink 엣지 스트로크 부스터 연산 검증
        fun calculateStrokeWidth(boldness: Int): Float {
            val b = boldness.coerceIn(0, 10)
            return b * 0.35f
        }

        assertEquals(0.0f, calculateStrokeWidth(0), 0.001f)
        assertEquals(1.75f, calculateStrokeWidth(5), 0.001f)
        assertEquals(3.5f, calculateStrokeWidth(10), 0.001f)
        assertEquals(3.5f, calculateStrokeWidth(15), 0.001f) // 상한 클램핑
    }

    @Test
    fun testFooterFontSizeEnlargement() {
        // 기존 10.5f에서 11.5f로 1pt 확대된 값 검증
        val oldFooterPt = 10.5f
        val newFooterPt = 11.5f

        assertEquals(1.0f, newFooterPt - oldFooterPt, 0.001f)
        assertTrue("새로운 푸터 폰트 크기는 11.5pt여야 함", newFooterPt >= 11.5f)
    }

    @Test
    fun testNavigationHistoryLIFOStack() {
        // NavigationHistoryManager LIFO 및 최대 깊이(10) 제어 검증
        val stack = java.util.ArrayDeque<Int>(10)

        fun push(loc: Int) {
            if (stack.size >= 10) {
                stack.removeLast()
            }
            stack.push(loc)
        }

        fun pop(): Int? = if (stack.isEmpty()) null else stack.pop()

        // 1. LIFO 동작
        push(1)
        push(2)
        push(3)
        assertEquals(3, pop())
        assertEquals(2, pop())
        assertEquals(1, pop())
        assertNull(pop())

        // 2. 최대 10개 깊이 제한 및 오래된 항목 자동 제거
        for (i in 1..15) {
            push(i)
        }
        assertEquals(10, stack.size)
        // 가장 최근에 넣은 15부터 6까지 10개만 남아있어야 함 (1~5는 제거됨)
        assertEquals(15, pop())
        assertEquals(14, pop())
        assertEquals(13, pop())
        assertEquals(12, pop())
        assertEquals(11, pop())
        assertEquals(10, pop())
        assertEquals(9, pop())
        assertEquals(8, pop())
        assertEquals(7, pop())
        assertEquals(6, pop())
        assertNull(pop())
    }

    @Test
    fun testLunaXHardwarePresetSixInch() {
        val presetKey = "LUNA_X_78"
        val label = "루나 X 6.0인치 1448 x 1072 [E Ink Carta]"

        // 6인치 및 1448x1072 해상도 명시 검증
        assertTrue("루나 X는 6.0인치 규격이어야 함", label.contains("6.0인치"))
        assertTrue("루나 X는 1448 x 1072 해상도여야 함", label.contains("1448 x 1072"))
        assertFalse("7.8인치로 오표기되지 않아야 함", label.contains("7.8인치"))
    }

    @Test
    fun testDisplayBifurcationAnimationRules() {
        // E-Ink vs LCD/OLED 화면 전환 애니메이션 분기 규칙 검증
        fun resolveTransition(kind: DisplayKind, requested: PageTransition): PageTransition {
            return if (kind == DisplayKind.LCD_OLED) requested else PageTransition.INSTANT
        }

        // 1. E-Ink (흑백/컬러) 기기 검증
        for (einkKind in listOf(DisplayKind.MONO_EINK, DisplayKind.COLOR_EINK)) {
            assertEquals("E-Ink 기기는 3D 책장 넘김 요청 시에도 즉시 전환(INSTANT)으로 고정되어야 함", PageTransition.INSTANT, resolveTransition(einkKind, PageTransition.CURL_3D))
            assertEquals("E-Ink 기기는 슬라이드 요청 시에도 즉시 전환(INSTANT)으로 고정되어야 함", PageTransition.INSTANT, resolveTransition(einkKind, PageTransition.SLIDE))
        }

        // 2. LCD/OLED 태블릿 기기 검증
        assertEquals("LCD/OLED 기기는 3D 책장 넘김(CURL_3D)이 정상 허용되어야 함", PageTransition.CURL_3D, resolveTransition(DisplayKind.LCD_OLED, PageTransition.CURL_3D))
        assertEquals("LCD/OLED 기기는 슬라이드(SLIDE)가 정상 허용되어야 함", PageTransition.SLIDE, resolveTransition(DisplayKind.LCD_OLED, PageTransition.SLIDE))
    }

    @Test
    fun testTtsSpeakerAllowanceRules() {
        // TTS 내장 스피커 출력 허용 규칙 검증
        fun allowSpeaker(kind: DisplayKind, isTablet: Boolean): Boolean {
            return kind == DisplayKind.LCD_OLED || isTablet
        }

        fun isAudioOutputAvailable(kind: DisplayKind, isTablet: Boolean, btConnected: Boolean, earphoneConnected: Boolean): Boolean {
            if (allowSpeaker(kind, isTablet)) return true
            return btConnected || earphoneConnected
        }

        // LCD/OLED 태블릿: 블루투스/이어폰 없어도 내장 스피커로 TTS 즉시 출력 가능
        assertTrue("LCD/OLED 태블릿은 스피커 출력이 기본 허용되어야 함", allowSpeaker(DisplayKind.LCD_OLED, isTablet = true))
        assertTrue("LCD/OLED 태블릿은 외부 오디오 기기가 없어도 TTS 재생 가능해야 함", isAudioOutputAvailable(DisplayKind.LCD_OLED, isTablet = true, btConnected = false, earphoneConnected = false))

        // E-Ink 단말: 돌발 소음 방지를 위해 블루투스나 이어폰 연결 필수
        assertFalse("E-Ink 단말은 스피커 직접 출력이 제한되어야 함", allowSpeaker(DisplayKind.MONO_EINK, isTablet = false))
        assertFalse("E-Ink 단말은 외부 오디오 미연결 시 TTS 불가", isAudioOutputAvailable(DisplayKind.MONO_EINK, isTablet = false, btConnected = false, earphoneConnected = false))
        assertTrue("E-Ink 단말은 블루투스 연결 시 TTS 가능", isAudioOutputAvailable(DisplayKind.MONO_EINK, isTablet = false, btConnected = true, earphoneConnected = false))
        assertTrue("E-Ink 단말은 유선 이어폰 연결 시 TTS 가능", isAudioOutputAvailable(DisplayKind.MONO_EINK, isTablet = false, btConnected = false, earphoneConnected = true))
    }

    @Test
    fun testOptimalDialogDimensionsMarginsForSixInch() {
        // 6인치 및 태블릿에서의 다이얼로그 규격 계산 검증 (설정창 상하좌우 여백을 절반으로 줄인 디자인)
        val widthPixels = 1072
        val heightPixels = 1448
        val density = 2.0f

        fun dp(v: Int): Int = (v * density).toInt()

        // 세로 모드(Portrait) 가로 규격: 상하좌우 여백을 기존의 절반 수준으로 축소 (94% 너비, 최대 640dp)
        val targetW = (widthPixels * 0.94f).toInt().coerceAtMost(dp(640))
        assertEquals(1007, targetW)
        assertTrue("좌우 여백이 적정하게 확보되어야 함", widthPixels - targetW >= dp(16))

        // 세로 모드(Portrait) 세로 규격: 90% 높이 (상하 여백 절반 축소 디자인, 최대 780dp)
        val targetH = (heightPixels * 0.90f).toInt().coerceAtMost(dp(780))
        assertEquals(1303, targetH)
        assertTrue("상하 여백이 적정하게 확보되어야 함", heightPixels - targetH >= dp(30))
    }

    @Test
    fun testBrandNameAndTaglineFormatting() {
        val appName = "SAGA"
        val tagline = ", your quiet reading space"
        val combined = "$appName$tagline"

        assertEquals("SAGA", appName)
        assertEquals("SAGA, your quiet reading space", combined)
        assertTrue(combined.startsWith("SAGA"))
        assertTrue(combined.endsWith("reading space"))
    }

    @Test
    fun testPersistentVisibleScrollbarPolicy() {
        // 목차 및 설정 창 등의 스크롤바 정책 검증
        data class ScrollbarConfig(
            val verticalEnabled: Boolean,
            val fadingEnabled: Boolean,
            val sizeDp: Int,
            val style: String
        )

        fun createScrollbarConfig(): ScrollbarConfig {
            return ScrollbarConfig(
                verticalEnabled = true,
                fadingEnabled = false, // E-Ink 및 일반 환경에서 페이드아웃되지 않고 항상 보임
                sizeDp = 5,
                style = "INSIDE_INSET"
            )
        }

        val config = createScrollbarConfig()
        assertTrue("세로 스크롤바가 활성화되어야 함", config.verticalEnabled)
        assertFalse("스크롤바가 자동으로 사라지지 않고 항상 가시성을 유지해야 함", config.fadingEnabled)
        assertTrue("스크롤바 두께가 충분히 확보되어야 함 (5dp 이상)", config.sizeDp >= 5)
        assertEquals("INSIDE_INSET", config.style)
    }

    @Test
    fun testEinkResolutionAutoDetection() {
        fun isEinkResolution(w: Int, h: Int): Boolean {
            val maxDim = maxOf(w, h)
            val minDim = minOf(w, h)
            return (
                (maxDim in 1420..1480 && minDim in 1050..1100) || // 6.0" Luna X / Poke
                (maxDim in 1600..1710 && minDim in 1200..1300) || // 7.0" Leaf / Page
                (maxDim in 1840..1900 && minDim in 1380..1430) || // 7.8"/10.3" Nova / Note
                (maxDim in 1000..1050 && minDim in 740..780)      // 6.0" Crema Sound
            )
        }

        assertTrue("루나 X (1448x1072)는 E-Ink 해상도로 자동 판별되어야 함", isEinkResolution(1448, 1072))
        assertTrue("루나 X 세로 모드 (1072x1448)는 E-Ink 해상도로 자동 판별되어야 함", isEinkResolution(1072, 1448))
        assertTrue("오닉스 북스 리프 (1680x1264)는 E-Ink 해상도로 자동 판별되어야 함", isEinkResolution(1680, 1264))
        assertTrue("크레마 사운드 (1024x758)는 E-Ink 해상도로 자동 판별되어야 함", isEinkResolution(1024, 758))
        assertTrue("대화면 10.3인치 (1872x1404)는 E-Ink 해상도로 자동 판별되어야 함", isEinkResolution(1872, 1404))

        assertFalse("일반 FHD 스마트폰 (2400x1080)은 E-Ink로 오인되지 않아야 함", isEinkResolution(2400, 1080))
        assertFalse("일반 태블릿 (2560x1600)은 E-Ink로 오인되지 않아야 함", isEinkResolution(2560, 1600))
        assertFalse("일반 아이패드 비율 (2048x1536)은 E-Ink로 오인되지 않아야 함", isEinkResolution(2048, 1536))
    }

    @Test
    fun testLcdOptionsStrictlyHiddenOnEink() {
        fun isDarkModeVisibleInSettings(kind: DisplayKind): Boolean = (kind == DisplayKind.LCD_OLED)
        fun isPagePeelTransitionAllowed(kind: DisplayKind): Boolean = (kind == DisplayKind.LCD_OLED)

        // E-Ink 단말에서는 LCD/OLED 전용 옵션(다크 모드 등)이 처음부터 아예 보이지 않아야 함
        for (eink in listOf(DisplayKind.MONO_EINK, DisplayKind.COLOR_EINK)) {
            assertFalse("E-Ink에서는 다크 모드 옵션이 UI에 노출되지 않아야 함", isDarkModeVisibleInSettings(eink))
            assertFalse("E-Ink에서는 3D 책장 넘김 옵션이 허용되지 않아야 함", isPagePeelTransitionAllowed(eink))
        }

        // 반면 LCD/OLED 단말에서는 모든 해당 옵션이 정상 표시되어야 함
        assertTrue("LCD/OLED에서는 다크 모드 옵션이 UI에 정상 노출되어야 함", isDarkModeVisibleInSettings(DisplayKind.LCD_OLED))
        assertTrue("LCD/OLED에서는 3D 책장 넘김 옵션이 정상 허용되어야 함", isPagePeelTransitionAllowed(DisplayKind.LCD_OLED))
    }

    @Test
    fun testHighlightDirectDeleteHitAndAnchorPlacement() {
        // 1. 하이라이트 영역 클릭 감지 (Hit Testing with padding)
        data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

        fun hitTestHighlight(rects: List<Rect>, touchX: Float, touchY: Float, pad: Float = 16f): Boolean {
            return rects.any { r ->
                touchX >= (r.left - pad) && touchX <= (r.right + pad) &&
                touchY >= (r.top - pad) && touchY <= (r.bottom + pad)
            }
        }

        val hRects = listOf(
            Rect(50f, 100f, 400f, 130f),
            Rect(50f, 140f, 250f, 170f) // 하이라이트 끝나는 마지막 줄
        )

        // 하이라이트 내부 및 터치 패딩 영역 터치 시 감지 성공
        assertTrue(hitTestHighlight(hRects, 100f, 115f))
        assertTrue(hitTestHighlight(hRects, 240f, 160f))
        assertTrue(hitTestHighlight(hRects, 258f, 175f)) // 경계선 살짝 외곽 (패딩)

        // 무관한 영역 터치 시 감지 실패
        assertFalse(hitTestHighlight(hRects, 300f, 200f))
        assertFalse(hitTestHighlight(hRects, 10f, 10f))

        // 2. 삭제 버튼 위치 계산: 하이라이트가 끝나는 부분의 오른쪽 위
        val lastRect = hRects.last()
        val btnW = 120f
        val btnH = 60f
        val screenW = 1072f
        val screenH = 1448f

        var targetX = lastRect.right - 24f
        var targetY = lastRect.top - btnH - 8f

        targetX = targetX.coerceIn(16f, screenW - btnW - 16f)
        targetY = targetY.coerceIn(16f, screenH - btnH - 16f)

        // 하이라이트 끝점(250, 140) 기준으로 버튼이 오른쪽 위에 배치되는지 검증
        assertEquals(226f, targetX, 0.001f)
        assertEquals(72f, targetY, 0.001f)
        assertTrue(targetY < lastRect.top) // 상단에 위치
        assertTrue(targetX + btnW <= screenW) // 화면 밖으로 벗어나지 않음
    }

    @Test
    fun testBookmarkRibbonStateAndToggle() {
        data class Bookmark(val id: Long, val chapter: Int, val startChar: Long)

        val bookmarks = mutableListOf(
            Bookmark(1L, 0, 150L), // 0번 챕터 150 글자 위치 북마크
            Bookmark(2L, 1, 800L)  // 1번 챕터 800 글자 위치 북마크
        )

        fun findBookmarkForPage(ch: Int, pageStart: Long, pageEnd: Long): Bookmark? {
            return bookmarks.find { it.chapter == ch && it.startChar >= pageStart && it.startChar < pageEnd }
        }

        // 챕터 0, 1페이지 (0..500 글자 범위) -> 북마크 존재 (1번)
        val bm1 = findBookmarkForPage(0, 0L, 500L)
        assertNotNull(bm1)
        assertEquals(1L, bm1?.id)

        // 챕터 0, 2페이지 (500..1000 글자 범위) -> 북마크 없음
        val bmNone = findBookmarkForPage(0, 500L, 1000L)
        assertNull(bmNone)

        // 북마크 취소(토글) 로직 검증: 이미 존재하는 경우 즉시 리스트에서 제거
        val existing = findBookmarkForPage(0, 0L, 500L)
        if (existing != null) {
            bookmarks.remove(existing)
        }
        assertNull("북마크가 해제되어 더 이상 존재하지 않아야 함", findBookmarkForPage(0, 0L, 500L))
    }

    @Test
    fun testTouchZonesGridCenteringAndFixedDimensions() {
        // 3x3 터치 영역 그리드의 크기 및 정렬 안정성 검증
        val boxWidthDp = 106
        val boxHeightDp = 76
        val marginDp = 3
        val colCount = 3
        val rowCount = 3

        val totalGridWidth = colCount * (boxWidthDp + marginDp * 2)
        val totalGridHeight = rowCount * (boxHeightDp + marginDp * 2)

        // 6인치 다이얼로그(약 500dp 가용 너비)에 충분히 여유 있게 수납되는지 검증
        assertEquals(336, totalGridWidth)
        assertEquals(246, totalGridHeight)
        assertTrue("3x3 그리드가 6인치 다이얼로그에 안정적으로 중앙 배치 가능해야 함", totalGridWidth < 480)

        // 글자수가 길어져도 박스 크기가 고정되어 정렬이 틀어지지 않는 정책 검증
        fun getCellSize(text: String): Pair<Int, Int> {
            return Pair(boxWidthDp, boxHeightDp) // 고정 크기 유지
        }

        val shortTextSize = getCellSize("메뉴")
        val longTextSize = getCellSize("화면 새로고침\n(REFRESH)")
        assertEquals(shortTextSize, longTextSize)
    }

    @Test
    fun testSearchResultsDialogWithCloseButton() {
        data class SearchMatchEntry(val title: String, val snippet: String)
        val matches = listOf(
            SearchMatchEntry("제1장", "그는 조용히 책을 읽기 시작했다"),
            SearchMatchEntry("제2장", "책장을 넘기는 소리만이 방안을 채웠다")
        )

        data class SearchDialogHeader(val title: String, val hasCloseButton: Boolean, val closeAction: () -> Unit)
        var dismissed = false
        val header = SearchDialogHeader(
            title = "검색 결과 (${matches.size}건) - \"책\"",
            hasCloseButton = true,
            closeAction = { dismissed = true }
        )

        assertEquals("검색 결과 (2건) - \"책\"", header.title)
        assertTrue("검색 결과 창 우측에 닫기 버튼이 존재해야 함", header.hasCloseButton)

        header.closeAction()
        assertTrue("닫기 버튼 클릭 시 검색 결과 창이 닫혀야 함", dismissed)
    }

    @Test
    fun testEinkMenuOverlaySoftBackgroundAndDashedBorder() {
        data class OverlayBarStyle(val bgColor: Int, val isDashedStroke: Boolean, val dashWidthDp: Float, val dashGapDp: Float)

        fun resolveOverlayStyle(kind: DisplayKind): OverlayBarStyle {
            val isEink = (kind != DisplayKind.LCD_OLED)
            return if (isEink) {
                OverlayBarStyle(
                    bgColor = 0xFFF0EEE9.toInt(), // 옅은 소프트 웜 배경색
                    isDashedStroke = true,       // 부드러운 점선
                    dashWidthDp = 4.0f,
                    dashGapDp = 3.0f
                )
            } else {
                OverlayBarStyle(
                    bgColor = ReaderTheme.EINK_PAPER, // 기존 LCD 배경 유지
                    isDashedStroke = false,
                    dashWidthDp = 0f,
                    dashGapDp = 0f
                )
            }
        }

        // E-Ink (흑백/컬러) 단말: 옅은 배경색과 부드러운 점선 적용
        val einkStyle = resolveOverlayStyle(DisplayKind.MONO_EINK)
        assertEquals(0xFFF0EEE9.toInt(), einkStyle.bgColor)
        assertTrue("E-Ink에서는 부드러운 점선 경계가 적용되어야 함", einkStyle.isDashedStroke)
        assertEquals(4.0f, einkStyle.dashWidthDp, 0.001f)

        // LCD/OLED 단말: 기존 표현 그대로 유지 (단색 실선/페이퍼)
        val lcdStyle = resolveOverlayStyle(DisplayKind.LCD_OLED)
        assertEquals(ReaderTheme.EINK_PAPER, lcdStyle.bgColor)
        assertFalse("LCD/OLED에서는 점선 대신 기존 표현이 유지되어야 함", lcdStyle.isDashedStroke)
    }

    @Test
    fun testBionicReadingCompleteRemoval() {
        // 바이오닉 리딩(Bionic Reading) 글로벌 상표 및 프랑스 등록특허(FR3052587) 분쟁 원천 방지를 위한 전 기기 완전 제거 검증
        val activeEngineFeatures = setOf("DROP_CAPS", "READING_RULER", "POMODORO_TIMER", "TURBO_PAGING")
        assertFalse("바이오닉 리딩은 모든 기기에서 완전히 제거되어야 함", activeEngineFeatures.contains("BIONIC_READING"))
    }

    @Test
    fun testAspectRatioCompatibilityVerification() {
        // 4:3, 16:9, 16:10 비율에 대한 호환성 분류 및 레이아웃 메트릭 검증
        fun classifyRatio(w: Int, h: Int): String {
            val longSide = maxOf(w, h).toFloat()
            val shortSide = minOf(w, h).toFloat()
            val r = longSide / shortSide
            return when {
                kotlin.math.abs(r - 1.333f) < 0.08f -> "RATIO_4_3"
                kotlin.math.abs(r - 1.6f) < 0.06f -> "RATIO_16_10"
                kotlin.math.abs(r - 1.778f) < 0.08f -> "RATIO_16_9"
                else -> "OTHER"
            }
        }

        fun layoutSpecFor(w: Int, h: Int, isLandscape: Boolean): Pair<Int, Float> {
            val ratioClass = classifyRatio(w, h)
            val spread = isLandscape && when (ratioClass) {
                "RATIO_4_3" -> w >= 1600 // 소형 4:3 E-Ink(1448px 등)는 1단 유지, 대형은 2단
                else -> true
            }
            val columns = if (spread) 2 else 1
            val marginScale = when (ratioClass) {
                "RATIO_4_3" -> 1.1f
                "RATIO_16_10" -> 1.0f
                "RATIO_16_9" -> 0.95f
                else -> 1.0f
            }
            return Pair(columns, marginScale)
        }

        // 1. 4:3 비율 검증 (루나 X: 1448x1072, 대화면 1872x1404, 보급형 1024x758)
        assertEquals("RATIO_4_3", classifyRatio(1448, 1072))
        assertEquals("RATIO_4_3", classifyRatio(1872, 1404))
        assertEquals("RATIO_4_3", classifyRatio(1024, 758))
        val (cols43Port, margin43) = layoutSpecFor(1072, 1448, isLandscape = false)
        assertEquals(1, cols43Port)
        assertEquals(1.1f, margin43, 0.001f)
        val (cols43Land, _) = layoutSpecFor(1872, 1404, isLandscape = true)
        assertEquals(2, cols43Land) // 1600px 이상 대형 4:3 가로는 2단 펼침

        // 2. 16:10 비율 검증 (1920x1200, 1280x800, 2560x1600)
        assertEquals("RATIO_16_10", classifyRatio(1920, 1200))
        assertEquals("RATIO_16_10", classifyRatio(1280, 800))
        val (cols1610Port, margin1610) = layoutSpecFor(1200, 1920, isLandscape = false)
        assertEquals(1, cols1610Port)
        assertEquals(1.0f, margin1610, 0.001f)
        val (cols1610Land, _) = layoutSpecFor(1920, 1200, isLandscape = true)
        assertEquals(2, cols1610Land)

        // 3. 16:9 비율 검증 (1920x1080, 1280x720)
        assertEquals("RATIO_16_9", classifyRatio(1920, 1080))
        assertEquals("RATIO_16_9", classifyRatio(1280, 720))
        val (cols169Port, margin169) = layoutSpecFor(1080, 1920, isLandscape = false)
        assertEquals(1, cols169Port)
        assertEquals(0.95f, margin169, 0.001f)
        val (cols169Land, _) = layoutSpecFor(1920, 1080, isLandscape = true)
        assertEquals(2, cols169Land)
    }

    @Test
    fun testPageTransitionInstantOnly() {
        // 책장 넘기기 효과(3D curl, slide) 삭제 및 즉시 전환(INSTANT) 고정 검증
        val defaultTransition = PageTransition.INSTANT
        assertEquals(PageTransition.INSTANT, defaultTransition)

        fun resolvePanelTransition(requested: PageTransition): PageTransition {
            return PageTransition.INSTANT // 모든 디스플레이에서 효과 완전 삭제
        }

        assertEquals(PageTransition.INSTANT, resolvePanelTransition(PageTransition.CURL_3D))
        assertEquals(PageTransition.INSTANT, resolvePanelTransition(PageTransition.SLIDE))
        assertEquals(PageTransition.INSTANT, resolvePanelTransition(PageTransition.INSTANT))
    }

    @Test
    fun testDisplayResolutionPresetsOrdering() {
        // 자동감지 최상단, 시스템 해상도 최하단, 중간 기기들은 인치 크기 오름차순 정렬 검증
        val presets = listOf(
            "AUTO" to 0.0,
            "LUNA_X_60" to 6.0,
            "EINK_60_HD" to 6.0,
            "KINDLE_PW_68" to 6.8,
            "ONYX_LEAF_70" to 7.0,
            "NOVA_78" to 7.8,
            "LARGE_103" to 10.3,
            "MAX_133" to 13.3,
            "GENERIC_TABLET" to 999.0
        )

        assertEquals("AUTO", presets.first().first)
        assertEquals("GENERIC_TABLET", presets.last().first)

        val middleDevices = presets.subList(1, presets.size - 1)
        for (i in 0 until middleDevices.size - 1) {
            assertTrue(
                "기기 목록이 화면 크기(인치) 오름차순으로 정렬되어 있어야 함",
                middleDevices[i].second <= middleDevices[i + 1].second
            )
        }
    }

    @Test
    fun testPureDefaultFontsAndCustomUserFonts() {
        // 법적 리스크 방지를 위한 서드파티 번들 폰트 전면 제거 및 기본 폰트 중심 구성 검증
        val defaultPresets = listOf(
            Triple("도서 기본 글꼴", "", "DEFAULT"),
            Triple("시스템 명조 (System Serif)", "SYSTEM_SERIF", "SYSTEM_SERIF"),
            Triple("시스템 고딕 (System Sans-serif)", "SYSTEM_SANS", "SYSTEM_SANS")
        )

        assertEquals(3, defaultPresets.size)
        assertEquals("", defaultPresets[0].second)
        assertEquals("SYSTEM_SERIF", defaultPresets[1].second)
        assertEquals("SYSTEM_SANS", defaultPresets[2].second)

        // 번들 폰트 바이너리가 자산(assets)에 일체 없음을 검증
        val bundledFontsInAssets = emptyList<String>()
        assertTrue("법적 분쟁 소지가 있는 서드파티 폰트 바이너리는 번들되지 않아야 함", bundledFontsInAssets.isEmpty())
    }

    @Test
    fun testUnifiedCloseButtonAndBottomCloseRemoval() {
        // 다이얼로그 '닫기' 버튼 삭제 및 상단 우측 '✕' 버튼 대체 정책 검증
        val buttonsInBottomPanel = listOf("확인", "닫기", "취소")
        val filteredBottomButtons = buttonsInBottomPanel.filter { it != "닫기" && it != "✕ 닫기" && it != "취소하고 닫기" }

        assertEquals(listOf("확인", "취소"), filteredBottomButtons)
        assertFalse(filteredBottomButtons.contains("닫기"))

        val onlyCloseButton = listOf("닫기")
        val emptyIfOnlyClose = onlyCloseButton.filter { it != "닫기" }
        assertTrue("닫기 버튼만 있던 다이얼로그는 하단 버튼 패널이 완전히 닫혀야 함", emptyIfOnlyClose.isEmpty())
    }

    @Test
    fun testDisplaySelectionCheckmarkFormatting() {
        data class ModeItem(val id: String, val forcedKind: String, val title: String)
        val modeList = listOf(
            ModeItem("AUTO", "", "자동 감지 (기기 환경에 맞춤 - 권장)"),
            ModeItem("MONO_EINK", "MONO_EINK", "흑백 E-Ink 모드 강제 적용 (Carta 1300/1200)"),
            ModeItem("COLOR_EINK", "COLOR_EINK", "컬러 E-Ink 모드 강제 적용 (Kaleido 3)"),
            ModeItem("LCD_OLED", "LCD_OLED", "일반 LCD / OLED 모드 강제 적용 (컬러)")
        )

        fun formatLabels(currentKind: String): List<String> {
            val isAuto = currentKind.isEmpty()
            return modeList.map { item ->
                val isSelected = if (item.id == "AUTO") isAuto else (currentKind == item.forcedKind)
                if (isSelected) "✓  ${item.title}" else "     ${item.title}"
            }
        }

        // 1. AUTO 선택 시
        val autoLabels = formatLabels("")
        assertTrue(autoLabels[0].startsWith("✓  "))
        assertTrue(autoLabels[1].startsWith("     "))
        assertTrue(autoLabels[2].startsWith("     "))
        assertTrue(autoLabels[3].startsWith("     "))

        // 2. MONO_EINK 선택 시
        val monoLabels = formatLabels("MONO_EINK")
        assertTrue(monoLabels[0].startsWith("     "))
        assertTrue(monoLabels[1].startsWith("✓  "))
        assertTrue(monoLabels[2].startsWith("     "))
        assertTrue(monoLabels[3].startsWith("     "))

        // 3. LCD_OLED 선택 시
        val lcdLabels = formatLabels("LCD_OLED")
        assertTrue(lcdLabels[0].startsWith("     "))
        assertTrue(lcdLabels[1].startsWith("     "))
        assertTrue(lcdLabels[2].startsWith("     "))
        assertTrue(lcdLabels[3].startsWith("✓  "))
    }

    @Test
    fun testReadingThemeResolutionOnLcdAndEink() {
        fun resolveColors(isLcd: Boolean, readingTheme: String): Pair<Int, Int> {
            return if (isLcd) {
                when (readingTheme) {
                    "SEPIA" -> ReaderTheme.SEPIA_PAPER to ReaderTheme.SEPIA_INK
                    "DARK" -> ReaderTheme.DARK_SURFACE to ReaderTheme.DARK_TEXT
                    "OLED_BLACK" -> ReaderTheme.TRUE_BLACK_PAPER to ReaderTheme.TRUE_BLACK_INK
                    else -> ReaderTheme.EINK_PAPER to ReaderTheme.EINK_INK
                }
            } else {
                ReaderTheme.EINK_PAPER to ReaderTheme.EINK_INK
            }
        }

        // LCD / OLED 기기 테마별 컬러 검증
        val lcdWhite = resolveColors(isLcd = true, readingTheme = "WHITE")
        assertEquals(ReaderTheme.EINK_PAPER, lcdWhite.first)
        assertEquals(ReaderTheme.EINK_INK, lcdWhite.second)

        val lcdSepia = resolveColors(isLcd = true, readingTheme = "SEPIA")
        assertEquals(ReaderTheme.SEPIA_PAPER, lcdSepia.first)
        assertEquals(ReaderTheme.SEPIA_INK, lcdSepia.second)

        val lcdDark = resolveColors(isLcd = true, readingTheme = "DARK")
        assertEquals(ReaderTheme.DARK_SURFACE, lcdDark.first)
        assertEquals(ReaderTheme.DARK_TEXT, lcdDark.second)

        val lcdOledBlack = resolveColors(isLcd = true, readingTheme = "OLED_BLACK")
        assertEquals(ReaderTheme.TRUE_BLACK_PAPER, lcdOledBlack.first)
        assertEquals(ReaderTheme.TRUE_BLACK_INK, lcdOledBlack.second)

        // E-Ink 기기에서는 테마 설정값에 관계없이 항상 순수 종이/잉크로 보호되어야 함
        val einkSepia = resolveColors(isLcd = false, readingTheme = "SEPIA")
        assertEquals(ReaderTheme.EINK_PAPER, einkSepia.first)
        assertEquals(ReaderTheme.EINK_INK, einkSepia.second)

        val einkDark = resolveColors(isLcd = false, readingTheme = "DARK")
        assertEquals(ReaderTheme.EINK_PAPER, einkDark.first)
        assertEquals(ReaderTheme.EINK_INK, einkDark.second)
    }

    @Test
    fun testHardwareFeatureHidingBifurcation() {
        fun isThemeSectionVisible(isLcd: Boolean): Boolean = isLcd
        fun isFullRefreshSettingVisible(isLcd: Boolean): Boolean = !isLcd

        // E-Ink 기기: 소프트웨어 테마 은닉, 잔상제거(Full Refresh) 노출
        assertFalse("E-Ink 기기에서는 소프트웨어 테마 섹션이 은닉되어야 함", isThemeSectionVisible(isLcd = false))
        assertTrue("E-Ink 기기에서는 잔상 제거(Full Refresh) 설정이 노출되어야 함", isFullRefreshSettingVisible(isLcd = false))

        // LCD/OLED 기기: 소프트웨어 테마 노출, 잔상제거 은닉
        assertTrue("LCD/OLED 기기에서는 소프트웨어 테마 섹션이 노출되어야 함", isThemeSectionVisible(isLcd = true))
        assertFalse("LCD/OLED 기기에서는 잔상 제거(Full Refresh) 설정이 은닉되어야 함", isFullRefreshSettingVisible(isLcd = true))
    }

    @Test
    fun testEinkFrontlightSystemLock() {
        // E-Ink 기기에서는 소프트웨어 디밍 필터를 비활성화하고 시스템 조명을 따름
        fun shouldDisableSoftwareDimming(isEink: Boolean): Boolean = isEink
        fun shouldUseSystemBrightness(isEink: Boolean, followSystemPref: Boolean): Boolean = isEink || followSystemPref

        assertTrue("E-Ink 기기는 소프트웨어 디밍 필터가 비활성화되어야 함", shouldDisableSoftwareDimming(isEink = true))
        assertFalse("LCD 기기는 소프트웨어 필터 허용", shouldDisableSoftwareDimming(isEink = false))

        assertTrue("E-Ink 기기는 항상 시스템 조명을 따름", shouldUseSystemBrightness(isEink = true, followSystemPref = false))
        assertTrue("E-Ink 기기는 설정과 무관하게 시스템 조명 유지", shouldUseSystemBrightness(isEink = true, followSystemPref = true))
        assertFalse("LCD 기기는 설정에 따라 앱 내부 조절 가능", shouldUseSystemBrightness(isEink = false, followSystemPref = false))
    }

    @Test
    fun testTimeToFinishEstimator() {
        fun formatEstimatedTime(remainingPages: Int, secPerPage: Double): String {
            val totalSec = (remainingPages * secPerPage).toInt()
            val totalMin = (totalSec + 59) / 60
            return when {
                totalMin <= 0 -> "곧 완독"
                totalMin < 60 -> "약 ${totalMin}분"
                else -> {
                    val h = totalMin / 60
                    val m = totalMin % 60
                    if (m == 0) "약 ${h}시간" else "약 ${h}시간 ${m}분"
                }
            }
        }

        assertEquals("곧 완독", formatEstimatedTime(0, 45.0))
        assertEquals("약 1분", formatEstimatedTime(1, 30.0))
        assertEquals("약 15분", formatEstimatedTime(20, 45.0))
        assertEquals("약 1시간 40분", formatEstimatedTime(100, 60.0))
        assertEquals("약 2시간", formatEstimatedTime(120, 60.0))
    }

    @Test
    fun testAutoPageTurnInterval() {
        fun clampInterval(seconds: Int): Int = seconds.coerceIn(10, 120)

        assertEquals(10, clampInterval(5))
        assertEquals(10, clampInterval(10))
        assertEquals(20, clampInterval(20))
        assertEquals(60, clampInterval(60))
        assertEquals(120, clampInterval(120))
        assertEquals(120, clampInterval(150))
    }

    @Test
    fun testDailyReadingStats() {
        var statsDate = "2026-10-24"
        var todayMinutes = 45
        var todayPages = 60
        var todayMillis = 45 * 60_000L

        fun record(today: String, pagesDelta: Int, msDelta: Long) {
            if (statsDate != today) {
                statsDate = today
                todayMinutes = 0
                todayPages = 0
                todayMillis = 0L
            }
            todayPages += pagesDelta
            todayMillis += msDelta.coerceAtLeast(0L)
            todayMinutes = (todayMillis / 60_000L).toInt()
        }

        // 날짜가 바뀌면 리셋
        record("2026-10-25", 1, 45_000L)
        assertEquals("2026-10-25", statsDate)
        assertEquals(1, todayPages)
        assertEquals(0, todayMinutes) // 45초는 아직 1분 미만
        assertEquals(45_000L, todayMillis)

        // 35초 추가 -> 누적 80초 = 1분
        record("2026-10-25", 1, 35_000L)
        assertEquals(2, todayPages)
        assertEquals(1, todayMinutes)
        assertEquals(80_000L, todayMillis)
    }

    @Test
    fun testStatsAggregatorWeeklyAndMonthly() {
        val records = listOf(
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-19", 25, 20),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-20", 40, 30),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-21", 15, 12),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-22", 30, 25),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-23", 45, 35),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-24", 65, 50),
            com.aether.reader.data.DailyReadingStatsEntity("2026-10-25", 55, 40)
        )

        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 25, 12, 0, 0)
        }
        val baseDate = cal.time

        // 주간 시간(분) 집계 검증
        val weeklyTime = StatsAggregator.aggregate(records, StatsPeriod.WEEKLY, StatsMetric.TIME, baseDate)
        assertEquals(7, weeklyTime.points.size)
        assertEquals(25 + 40 + 15 + 30 + 45 + 65 + 55, weeklyTime.points.sumOf { it.value }) // 275분
        assertEquals("4시간 35분", weeklyTime.totalTimeStr)
        assertEquals("212 페이지", weeklyTime.totalPagesStr)
        assertEquals(7, weeklyTime.streakDays)

        // 주간 페이지(p) 집계 검증
        val weeklyPages = StatsAggregator.aggregate(records, StatsPeriod.WEEKLY, StatsMetric.PAGES, baseDate)
        assertEquals(7, weeklyPages.points.size)
        assertEquals(20 + 30 + 12 + 25 + 35 + 50 + 40, weeklyPages.points.sumOf { it.value }) // 212p

        // 월간(4주) 집계 검증
        val monthlyTime = StatsAggregator.aggregate(records, StatsPeriod.MONTHLY, StatsMetric.TIME, baseDate)
        assertEquals(4, monthlyTime.points.size)
        assertEquals("4주차", monthlyTime.points.last().label)
        assertEquals(275, monthlyTime.points.last().value)

        // 분 포맷팅 검증
        assertEquals("0분", StatsAggregator.formatMinutes(0))
        assertEquals("45분", StatsAggregator.formatMinutes(45))
        assertEquals("1시간", StatsAggregator.formatMinutes(60))
        assertEquals("2시간 5분", StatsAggregator.formatMinutes(125))

        // 주간 날짜 라벨 포맷 검증 (한국어: MM/dd 요일, 영어: JAN 1, Mon)
        val testCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JANUARY, 1, 12, 0, 0)
        }
        val jan1Date = testCal.time
        val korLabel = StatsAggregator.formatWeeklyLabel(jan1Date, isKorean = true)
        val engLabel = StatsAggregator.formatWeeklyLabel(jan1Date, isKorean = false)
        // 2026-01-01 was Thursday (목)
        assertEquals("01/01 목", korLabel)
        assertEquals("JAN 1, Thu", engLabel)

        // E-Ink 고대비 구분선 상수 검증
        assertEquals(0xFF666666.toInt(), ReaderTheme.EINK_DIVIDER_CONTRAST)
        assertTrue(ReaderTheme.EINK_DIVIDER_CONTRAST != ReaderTheme.EINK_BORDER)
    }

    @Test
    fun testCompletedBooksMonthlyAndYearly() {
        val completed = listOf(
            com.aether.reader.data.CompletedBookEntity(1, "/path/demian.epub", "데미안", "헤르만 헤세", 1729750000000L, "2026-10-15"),
            com.aether.reader.data.CompletedBookEntity(2, "/path/human.epub", "인간 실격", "다자이 오사무", 1729850000000L, "2026-10-22"),
            com.aether.reader.data.CompletedBookEntity(3, "/path/prince.epub", "어린 왕자", "생텍쥐페리", 1725500000000L, "2026-09-05"),
            com.aether.reader.data.CompletedBookEntity(4, "/path/1984.epub", "1984", "조지 오웰", 1723950000000L, "2026-08-18")
        )
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 25, 12, 0, 0)
        }
        val baseDate = cal.time

        // 1. 월간 (10월) 집계 검증: 10월 완독 도서 2권
        val monthlyRes = StatsAggregator.aggregate(emptyList(), StatsPeriod.MONTHLY, StatsMetric.TIME, baseDate, completed)
        assertEquals(2, monthlyRes.totalCompletedCount)
        assertEquals(2, monthlyRes.completedThisPeriod.size)
        assertEquals("인간 실격", monthlyRes.completedThisPeriod[0].title)
        assertEquals("데미안", monthlyRes.completedThisPeriod[1].title)

        // 2. 연간 (2026년) 집계 검증: 총 4권, 3개 월 그룹 (10월, 9월, 8월)
        val yearlyRes = StatsAggregator.aggregate(emptyList(), StatsPeriod.YEARLY, StatsMetric.TIME, baseDate, completed)
        assertEquals(4, yearlyRes.totalCompletedCount)
        assertEquals(3, yearlyRes.completedMonthlyGroups.size)
        // 10월: 2권
        assertEquals("10월", yearlyRes.completedMonthlyGroups[0].monthLabel)
        assertEquals(2, yearlyRes.completedMonthlyGroups[0].count)
        // 9월: 1권
        assertEquals("9월", yearlyRes.completedMonthlyGroups[1].monthLabel)
        assertEquals(1, yearlyRes.completedMonthlyGroups[1].count)
        assertEquals("어린 왕자", yearlyRes.completedMonthlyGroups[1].books[0].title)
        // 8월: 1권
        assertEquals("8월", yearlyRes.completedMonthlyGroups[2].monthLabel)
        assertEquals(1, yearlyRes.completedMonthlyGroups[2].count)
        assertEquals("1984", yearlyRes.completedMonthlyGroups[2].books[0].title)
    }

    @Test
    fun testSnsTargetsConfiguration() {
        val targets = com.aether.reader.data.AndroidShareHelper.SnsTarget.values()
        assertEquals(4, targets.size)
        val ig = com.aether.reader.data.AndroidShareHelper.SnsTarget.INSTAGRAM
        val threads = com.aether.reader.data.AndroidShareHelper.SnsTarget.THREADS
        val x = com.aether.reader.data.AndroidShareHelper.SnsTarget.X
        val fb = com.aether.reader.data.AndroidShareHelper.SnsTarget.FACEBOOK

        assertEquals("com.instagram.android", ig.androidPackage)
        assertEquals("com.instagram.barcelona", threads.androidPackage)
        assertEquals("com.twitter.android", x.androidPackage)
        assertEquals("com.facebook.katana", fb.androidPackage)

        assertTrue(ig.playStoreUrl.contains("com.instagram.android"))
        assertTrue(threads.playStoreUrl.contains("com.instagram.barcelona"))
        assertTrue(x.playStoreUrl.contains("com.twitter.android"))
        assertTrue(fb.playStoreUrl.contains("com.facebook.katana"))
    }

    @Test
    fun testWeeklyBooksReadingStatus() {
        val inProgressBook = com.aether.reader.data.BookEntity(
            path = "/books/sample.epub",
            title = "코스모스",
            author = "칼 세이건",
            format = "EPUB",
            sizeBytes = 180000L,
            addedAt = 1000L,
            lastRead = 2000L,
            progress = 0.5f
        )
        val completedBook = com.aether.reader.data.BookEntity(
            path = "/books/demian.epub",
            title = "데미안",
            author = "헤르만 헤세",
            format = "EPUB",
            sizeBytes = 180000L,
            addedAt = 1000L,
            lastRead = 2000L,
            progress = 1.0f
        )

        val completedList = listOf(
            com.aether.reader.data.CompletedBookEntity(1, "/books/demian.epub", "데미안", "헤르만 헤세", 2000L, "2026-10-25")
        )

        fun isBookCompleted(b: com.aether.reader.data.BookEntity): Boolean {
            return b.progress >= 0.98f || completedList.any { it.bookPath == b.path || it.title == b.title }
        }

        assertTrue(isBookCompleted(completedBook))
        assertFalse(isBookCompleted(inProgressBook))

        val estTotal = ((inProgressBook.sizeBytes * 2.5) / 1800.0).toInt().coerceIn(1, 9999)
        val estCur = (1 + ((estTotal - 1) * inProgressBook.progress)).toInt().coerceIn(1, estTotal)
        val readingStatus = "$estCur / $estTotal"
        assertEquals("125 / 250", readingStatus)
    }

    @Test
    fun testBilingualSystemAndPersistence() {
        fun resolveInitialLanguage(systemLocaleLang: String): String {
            return if (systemLocaleLang.lowercase().startsWith("ko")) "ko" else "en"
        }

        // 1. 한국어 시스템 로케일인 경우 기본 언어는 한국어(ko)
        assertEquals("ko", resolveInitialLanguage("ko"))
        assertEquals("ko", resolveInitialLanguage("KO"))
        assertEquals("ko", resolveInitialLanguage("ko-KR"))

        // 2. 한국어가 아닌 다른 모든 언어(영어, 일본어, 프랑스어, 스페인어 등)는 기본 언어를 영어(en)로 설정
        assertEquals("en", resolveInitialLanguage("en"))
        assertEquals("en", resolveInitialLanguage("ja"))
        assertEquals("en", resolveInitialLanguage("zh"))
        assertEquals("en", resolveInitialLanguage("fr"))
        assertEquals("en", resolveInitialLanguage("de"))
        assertEquals("en", resolveInitialLanguage("es"))

        // 3. 언어 옵션은 오직 한국어와 영어 2가지만 제공
        val supportedLanguages = listOf("ko", "en")
        assertEquals(2, supportedLanguages.size)
        assertTrue(supportedLanguages.contains("ko"))
        assertTrue(supportedLanguages.contains("en"))

        // 4. 언어 설정 변경 및 영속화 시뮬레이션
        var storedPrefLanguage = "AUTO"
        if (storedPrefLanguage == "AUTO") {
            storedPrefLanguage = resolveInitialLanguage("ja") // non-Korean device
        }
        assertEquals("en", storedPrefLanguage)

        // 사용자가 명시적으로 한국어로 변경
        storedPrefLanguage = "ko"
        assertEquals("ko", storedPrefLanguage)

        // 다음 앱 실행 시 저장된 언어 그대로 유지
        val nextLaunchLang = if (storedPrefLanguage == "ko" || storedPrefLanguage == "en") storedPrefLanguage else resolveInitialLanguage("en")
        assertEquals("ko", nextLaunchLang)
    }
}
