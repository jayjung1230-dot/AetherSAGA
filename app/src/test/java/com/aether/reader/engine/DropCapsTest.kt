package com.aether.reader.engine

import org.junit.Assert.*
import org.junit.Test

class DropCapsTest {

    @Test
    fun testNormalKoreanFirstWord() {
        val text = "가을은 독서의 계절이다."
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull("Emphasis range should not be null", range)
        assertEquals(0, range!!.start)
        assertEquals(3, range.end)
        assertEquals("가을은", text.substring(range.start, range.end))
    }

    @Test
    fun testFirstWordWithLeadingWhitespaceAndNewlines() {
        val text = "\r\n  \n   안녕하세요. 반갑습니다."
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull("Should skip leading whitespace and newlines", range)
        assertEquals("안녕하세요.", text.substring(range!!.start, range.end))
    }

    @Test
    fun testFirstWordDialogueWithKoreanOpeningQuotes() {
        val text = "“이것은 중요한 첫 문장입니다.”"
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull("Should include opening quote and first word", range)
        assertEquals("“이것은", text.substring(range!!.start, range.end))
    }

    @Test
    fun testFirstWordDialogueWithStandardDoubleQuotes() {
        val text = "\n  \"Hello reader!\""
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull("Should find first word even after whitespace", range)
        assertEquals("\"Hello", text.substring(range!!.start, range.end))
    }

    @Test
    fun testFirstWordDialogueWithSingleQuotes() {
        val text = "'그때 우리는 떠났다.'"
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull(range)
        assertEquals("'그때", text.substring(range!!.start, range.end))
    }

    @Test
    fun testNumberedChapterHeadingWord() {
        val text = "1장. 시작하며"
        val range = DropCapsFormatter.findEmphasisRange(text)
        assertNotNull("Should support number-prefixed headings", range)
        assertEquals("1장.", text.substring(range!!.start, range.end))
    }

    @Test
    fun testEmptyOrWhitespaceOnlySafety() {
        assertNull(DropCapsFormatter.findEmphasisRange(""))
        assertNull(DropCapsFormatter.findEmphasisRange("   \r\n \t "))
    }

    @Test
    fun testSentenceRangeNeverSpillsAcrossNewline() {
        val text = "첫 번째 줄의 문장입니다\n두 번째 줄의 문장입니다\n세 번째 줄의 문장입니다"
        // Touch inside line 1
        val r1 = DropCapsFormatter.findSentenceRange(text, 3)
        assertNotNull(r1)
        assertEquals("첫 번째 줄의 문장입니다", text.substring(r1!!.start, r1.end))
        assertFalse("Must never include newline", text.substring(r1.start, r1.end).contains("\n"))
        // Last char index (end - 1) must be strictly on line 1 (index 12)
        assertEquals(12, r1.end - 1)

        // Touch right at the newline of line 1 (index 13) -> must stay on line 1
        val r1End = DropCapsFormatter.findSentenceRange(text, 13)
        assertNotNull(r1End)
        assertEquals("첫 번째 줄의 문장입니다", text.substring(r1End!!.start, r1End.end))
    }

    @Test
    fun testMultipleSentencesOnSameLine() {
        val text = "짧은 첫 문장입니다. 이어지는 두 번째 문장입니다. 마지막 세 번째 문장!"
        val r1 = DropCapsFormatter.findSentenceRange(text, 2)
        assertEquals("짧은 첫 문장입니다.", text.substring(r1!!.start, r1.end))

        val r2 = DropCapsFormatter.findSentenceRange(text, 16)
        assertEquals("이어지는 두 번째 문장입니다.", text.substring(r2!!.start, r2.end))

        val r3 = DropCapsFormatter.findSentenceRange(text, 33)
        assertEquals("마지막 세 번째 문장!", text.substring(r3!!.start, r3.end))
    }

    @Test
    fun testDialogueSentenceWithClosingQuotes() {
        val text = "“오늘 날씨가 참 좋네요.” 그가 미소 지으며 말했다."
        val r1 = DropCapsFormatter.findSentenceRange(text, 4)
        assertEquals("“오늘 날씨가 참 좋네요.”", text.substring(r1!!.start, r1.end))

        val r2 = DropCapsFormatter.findSentenceRange(text, 20)
        assertEquals("그가 미소 지으며 말했다.", text.substring(r2!!.start, r2.end))
    }
}
