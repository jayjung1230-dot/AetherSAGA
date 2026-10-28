package com.aether.reader.engine

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Initial Word Emphasis Pipeline (첫 단어 강조) & Sentence Boundary Detector (독서 룰러 문장 단위 인식).
 */
object DropCapsFormatter {

    data class EmphasisRange(val start: Int, val end: Int)

    /**
     * Finds the first substantive word (어절/단어) of a chapter, skipping leading whitespace/BOM/newlines.
     */
    fun findEmphasisRange(text: CharSequence): EmphasisRange? {
        if (text.isEmpty()) return null
        var start = 0
        while (start < text.length && (text[start].isWhitespace() || text[start] == '\uFEFF' || text[start] == '\uFFFC')) {
            start++
        }
        if (start >= text.length) return null

        var end = start
        val maxWordLen = 24
        while (end < text.length && !text[end].isWhitespace() && text[end] != '\uFFFC' && (end - start) < maxWordLen) {
            end++
        }
        return if (end > start) EmphasisRange(start, end) else null
    }

    /**
     * Finds the exact [start, end) character range of the sentence containing [offset].
     * Guarantees [end] never includes trailing newlines or whitespace so line mapping stays strictly on the sentence's lines.
     */
    fun findSentenceRange(text: CharSequence, offset: Int): EmphasisRange? {
        if (text.isEmpty()) return null
        var off = offset.coerceIn(0, text.length - 1)

        val stops = ".!?。！？\n"
        val closingQuotes = "\"”'’」』)]}>"

        if (text[off] == '\n' && off > 0) off--
        while (off < text.length && (text[off] == ' ' || text[off] == '\t') && off + 1 < text.length && text[off + 1] != '\n') {
            off++
        }
        if (text[off] in closingQuotes && off > 0 && text[off - 1] in stops) {
            off--
        }

        var s = off
        while (s > 0 && off - s < 500) {
            val prev = text[s - 1]
            if (prev in stops) break
            if (prev in closingQuotes && s >= 2 && text[s - 2] in stops) break
            s--
        }

        var e = off
        while (e < text.length && e - off < 500) {
            val ch = text[e]
            if (ch == '\n') {
                break
            }
            if (ch in stops) {
                e++
                while (e < text.length && text[e] in closingQuotes) {
                    e++
                }
                break
            }
            e++
        }

        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (e > s) EmphasisRange(s, e) else null
    }

    fun apply(sb: SpannableStringBuilder, scale: Float = 1.35f): SpannableStringBuilder {
        val range = findEmphasisRange(sb) ?: return sb
        sb.setSpan(
            StyleSpan(Typeface.BOLD),
            range.start,
            range.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            RelativeSizeSpan(scale),
            range.start,
            range.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }
}
